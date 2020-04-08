package jms4s

import cats.Functor
import cats.data.NonEmptyList
import cats.effect.{ Concurrent, ContextShift, Resource }
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import jms4s.JmsTransactedConsumer.JmsTransactedConsumerPool.{ JmsResource, Received }
import jms4s.JmsTransactedConsumer.TransactionAction
import jms4s.config.DestinationName
import jms4s.jms._
import jms4s.model.SessionType.Transacted

import scala.concurrent.duration.FiniteDuration

trait JmsTransactedConsumer[F[_]] {
  def handle(f: JmsMessage[F] => F[TransactionAction[F]]): F[Unit]
}

object JmsTransactedConsumer {

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    concurrencyLevel: Int
  ): Resource[F, JmsTransactedConsumer[F]] =
    for {
      input <- Resource.liftF(connection.createSession(Transacted).use(_.createDestination(inputDestinationName)))
      pool  <- Resource.liftF(Queue.bounded[F, JmsResource[F]](concurrencyLevel))
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session  <- connection.createSession(Transacted)
              consumer <- session.createConsumer(input)
              _ <- Resource.liftF(
                    pool.enqueue1(JmsResource(session, consumer, Map.empty, new MessageFactory[F](session)))
                  )
            } yield ()
          }
    } yield build(new JmsTransactedConsumerPool(pool), concurrencyLevel)

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    outputDestinationNames: NonEmptyList[DestinationName],
    concurrencyLevel: Int
  ): Resource[F, JmsTransactedConsumer[F]] =
    for {
      inputDestination <- Resource.liftF(
                           connection
                             .createSession(Transacted)
                             .use(_.createDestination(inputDestinationName))
                         )
      outputDestinations <- Resource.liftF(
                             outputDestinationNames
                               .traverse(
                                 outputDestinationName =>
                                   connection
                                     .createSession(Transacted)
                                     .use(_.createDestination(outputDestinationName))
                                     .map(jmsDestination => (outputDestinationName, jmsDestination))
                               )
                           )
      pool <- Resource.liftF(
               Queue.bounded[F, JmsResource[F]](concurrencyLevel)
             )
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session  <- connection.createSession(Transacted)
              consumer <- session.createConsumer(inputDestination)
              producers <- outputDestinations.traverse {
                            case (outputDestinationName, outputDestination) =>
                              session
                                .createProducer(outputDestination)
                                .map(jmsProducer => (outputDestinationName, new JmsProducer(jmsProducer)))
                          }.map(_.toNem)
              _ <- Resource.liftF(
                    pool.enqueue1(JmsResource(session, consumer, producers.toSortedMap, new MessageFactory[F](session)))
                  )
            } yield ()
          }
    } yield build(new JmsTransactedConsumerPool(pool), concurrencyLevel)

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    connection: JmsConnection[F],
    inputDestinationName: DestinationName,
    outputDestinationName: DestinationName,
    concurrencyLevel: Int
  ): Resource[F, JmsTransactedConsumer[F]] =
    for {
      inputDestination <- Resource.liftF(
                           connection
                             .createSession(Transacted)
                             .use(_.createDestination(inputDestinationName))
                         )
      outputDestination <- Resource.liftF(
                            connection
                              .createSession(Transacted)
                              .use(_.createDestination(outputDestinationName))
                          )
      pool <- Resource.liftF(Queue.bounded[F, JmsResource[F]](concurrencyLevel))
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              session     <- connection.createSession(Transacted)
              consumer    <- session.createConsumer(inputDestination)
              jmsProducer <- session.createProducer(outputDestination)
              producer    = Map(outputDestinationName -> new JmsProducer(jmsProducer))
              _ <- Resource.liftF(
                    pool.enqueue1(JmsResource(session, consumer, producer, new MessageFactory[F](session)))
                  )
            } yield ()
          }
    } yield build(new JmsTransactedConsumerPool(pool), concurrencyLevel)

  private def build[F[_]: ContextShift: Concurrent](
    pool: JmsTransactedConsumerPool[F],
    concurrencyLevel: Int
  ): JmsTransactedConsumer[F] =
    (f: JmsMessage[F] => F[TransactionAction[F]]) =>
      Stream
        .emits(0 until concurrencyLevel)
        .as(
          Stream.eval(
            fo = for {
              received <- pool.receive
              tResult  <- f(received.message)
              _ <- tResult.fold(
                    pool.commit(received.resource),
                    pool.rollback(received.resource),
                    send => {
                      val createMessages = send.createMessages
                      createMessages(received.resource.messageFactory)
                        .flatMap(
                          toSend =>
                            toSend.messagesAndDestinations.traverse_ {
                              case (message, (name, delay)) =>
                                delay.fold(
                                  received.resource
                                    .producers(name)
                                    .publish(message)
                                )(
                                  d =>
                                    received.resource
                                      .producers(name)
                                      .publish(message, d)
                                ) *> pool.commit(received.resource)
                            }
                        )
                    }
                  )
            } yield ()
          )
        )
        .parJoin(concurrencyLevel)
        .repeat
        .compile
        .drain

  private[jms4s] class JmsTransactedConsumerPool[F[_]: Concurrent: ContextShift](pool: Queue[F, JmsResource[F]]) {

    val receive: F[Received[F]] =
      for {
        resource <- pool.dequeue1
        msg      <- resource.consumer.receiveJmsMessage
      } yield Received(msg, resource)

    def commit(resource: JmsResource[F]): F[Unit] =
      for {
        _ <- resource.session.commit
        _ <- pool.enqueue1(resource)
      } yield ()

    def rollback(resource: JmsResource[F]): F[Unit] =
      for {
        _ <- resource.session.rollback
        _ <- pool.enqueue1(resource)
      } yield ()
  }

  object JmsTransactedConsumerPool {

    private[jms4s] case class JmsResource[F[_]] private[jms4s] (
      session: JmsSession[F],
      consumer: JmsMessageConsumer[F],
      producers: Map[DestinationName, JmsProducer[F]],
      messageFactory: MessageFactory[F]
    )

    private[jms4s] case class Received[F[_]] private (message: JmsMessage[F], resource: JmsResource[F])

  }

  sealed abstract class TransactionAction[F[_]] extends Product with Serializable {
    def fold(ifCommit: => F[Unit], ifRollback: => F[Unit], ifSend: TransactionAction.Send[F] => F[Unit]): F[Unit]
  }

  object TransactionAction {

    private[jms4s] case class Commit[F[_]]() extends TransactionAction[F] {
      override def fold(ifCommit: => F[Unit], ifRollback: => F[Unit], ifSend: Send[F] => F[Unit]): F[Unit] = ifCommit
    }

    private[jms4s] case class Rollback[F[_]]() extends TransactionAction[F] {
      override def fold(ifCommit: => F[Unit], ifRollback: => F[Unit], ifSend: Send[F] => F[Unit]): F[Unit] = ifRollback
    }

    case class Send[F[_]](
      createMessages: MessageFactory[F] => F[ToSend[F]]
    ) extends TransactionAction[F] {
      override def fold(ifCommit: => F[Unit], ifRollback: => F[Unit], ifSend: Send[F] => F[Unit]): F[Unit] =
        ifSend(this)
    }

    private[jms4s] case class ToSend[F[_]](
      messagesAndDestinations: NonEmptyList[(JmsMessage[F], (DestinationName, Option[FiniteDuration]))]
    )

    def commit[F[_]]: TransactionAction[F] = Commit[F]()

    def rollback[F[_]]: TransactionAction[F] = Rollback[F]()

    def sendN[F[_]: Functor](
      messageFactory: MessageFactory[F] => F[NonEmptyList[(JmsMessage[F], DestinationName)]]
    ): Send[F] =
      Send[F](
        mf => messageFactory(mf).map(nel => nel.map { case (message, name) => (message, (name, None)) }).map(ToSend[F])
      )

    def sendNWithDelay[F[_]: Functor](
      messageFactory: MessageFactory[F] => F[NonEmptyList[(JmsMessage[F], (DestinationName, Option[FiniteDuration]))]]
    ): Send[F] =
      Send[F](mf => messageFactory(mf).map(ToSend[F]))

    def sendWithDelay[F[_]: Functor](
      messageFactory: MessageFactory[F] => F[(JmsMessage[F], (DestinationName, Option[FiniteDuration]))]
    ): Send[F] =
      Send[F](mf => messageFactory(mf).map(x => ToSend[F](NonEmptyList.one(x))))

    def send[F[_]: Functor](messageFactory: MessageFactory[F] => F[(JmsMessage[F], DestinationName)]): Send[F] =
      Send[F](
        mf => messageFactory(mf).map { case (message, name) => ToSend[F](NonEmptyList.one((message, (name, None)))) }
      )
  }
}