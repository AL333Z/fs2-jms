# jms4s - a cats-effects wrapper for jms 
[![Build Status](https://travis-ci.com/fp-in-bo/jms4s.svg?branch=master)](https://travis-ci.com/fpinbo/jms4s)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/dev.fpinbo/jms4s_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/dev.fpinbo/jms4s_2.12)
![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)
[![Mergify Status][mergify-status]][mergify]
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

[mergify]: https://mergify.io
[mergify-status]: https://img.shields.io/endpoint.svg?url=https://gh.mergify.io/badges/fp-in-bo/jms4s&style=flat

Nobody really wants to use jms, but if you have no choice or you're not like us you may find this useful.

## Supported features:

- Consuming, returning a never-ending cancellable program that can concurrently consumes from a queue
  -  createQueueTransactedConsumer
  -  createQueueAckConsumer
  -  createQueueAutoAckConsumer

- Publishing, returning a program which can publish messages
  - createQueuePublisher
  - createTopicPublisher

- Consuming and Publishing within the same local transaction

## [Head on over to the microsite](https://fp-in-bo.github.io/jms4s)

## Quick Start

To use jms4s in an existing SBT project with Scala 2.12 or a later version, add the following dependencies to your
`build.sbt` depending on your provider:

```scala
libraryDependencies ++= Seq(
  "dev.fpinbo" %% "jms4s-active-mq-artemis" % "<version>", // if your provider is activemq-artemis
  "dev.fpinbo" %% "jms4s-ibm-mq"            % "<version>"  // if your provider is ibmmq
)
```

## Local dev

- `docker-compose up -d`
- `sbt test`
