package jms4s

import cats.data.NonEmptyList
import cats.effect.{ Concurrent, ContextShift, Resource }
import cats.syntax.all._
import fs2.concurrent.Queue
import jms4s.config.DestinationName
import jms4s.jms._
import jms4s.model.SessionType

import scala.concurrent.duration.FiniteDuration

trait JmsProducer[F[_]] {

  def sendN(
    messageFactory: MessageFactory[F] => F[NonEmptyList[(JmsMessage, DestinationName)]]
  ): F[Unit]

  def sendNWithDelay(
    messageFactory: MessageFactory[F] => F[NonEmptyList[(JmsMessage, (DestinationName, Option[FiniteDuration]))]]
  ): F[Unit]

  def sendWithDelay(
    messageFactory: MessageFactory[F] => F[(JmsMessage, (DestinationName, Option[FiniteDuration]))]
  ): F[Unit]

  def send(messageFactory: MessageFactory[F] => F[(JmsMessage, DestinationName)]): F[Unit]

}

object JmsProducer {

  private[jms4s] def make[F[_]: ContextShift: Concurrent](
    context: JmsContext[F],
    concurrencyLevel: Int
  ): Resource[F, JmsProducer[F]] =
    for {
      pool <- Resource.liftF(
               Queue.bounded[F, JmsContext[F]](concurrencyLevel)
             )
      _ <- (0 until concurrencyLevel).toList.traverse_ { _ =>
            for {
              c <- context.createContext(SessionType.AutoAcknowledge)
              _ <- Resource.liftF(pool.enqueue1(c))
            } yield ()
          }
      mf = MessageFactory[F](context)
    } yield new JmsProducer[F] {

      override def sendN(
        f: MessageFactory[F] => F[NonEmptyList[(JmsMessage, DestinationName)]]
      ): F[Unit] =
        for {
          ctx                      <- pool.dequeue1
          messagesWithDestinations <- f(mf)
          _ <- messagesWithDestinations.traverse_ {
                case (message, destinationName) => ctx.send(destinationName, message)
              }
          _ <- pool.enqueue1(ctx)
        } yield ()

      override def sendNWithDelay(
        f: MessageFactory[F] => F[NonEmptyList[(JmsMessage, (DestinationName, Option[FiniteDuration]))]]
      ): F[Unit] =
        for {
          ctx                                <- pool.dequeue1
          messagesWithDestinationsAndDelayes <- f(mf)
          _ <- messagesWithDestinationsAndDelayes.traverse_ {
                case (message, (destinatioName, duration)) =>
                  duration.fold(ctx.send(destinatioName, message))(delay => ctx.send(destinatioName, message, delay))
              }
          _ <- pool.enqueue1(ctx)
        } yield ()

      override def sendWithDelay(
        f: MessageFactory[F] => F[(JmsMessage, (DestinationName, Option[FiniteDuration]))]
      ): F[Unit] =
        for {
          ctx                                 <- pool.dequeue1
          (message, (destinationName, delay)) <- f(mf)
          _                                   <- delay.fold(ctx.send(destinationName, message))(delay => ctx.send(destinationName, message, delay))
          _                                   <- pool.enqueue1(ctx)
        } yield ()

      override def send(f: MessageFactory[F] => F[(JmsMessage, DestinationName)]): F[Unit] =
        for {
          ctx                    <- pool.dequeue1
          (message, destination) <- f(mf)
          _                      <- ctx.send(destination, message)
          _                      <- pool.enqueue1(ctx)
        } yield ()
    }
}
