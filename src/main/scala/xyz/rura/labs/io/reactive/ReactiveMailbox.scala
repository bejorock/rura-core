package xyz.rura.labs.io.reactive

import akka.dispatch.PriorityGenerator
import akka.dispatch.UnboundedStablePriorityMailbox
import akka.dispatch.BoundedStablePriorityMailbox
import akka.actor.ActorSystem
import akka.actor.PoisonPill

import com.typesafe.config.Config

import xyz.rura.labs.io._

import ReactiveStream.{Request, Response, EOF, SetupWorker, Error}

import scala.concurrent.duration._

class ReactiveMailbox(settings:ActorSystem.Settings, config:Config) extends UnboundedStablePriorityMailbox(PriorityGenerator {
	// high priority
	case SetupWorker(mapperProps, nextTarget, num) => 0
	case Request(vf) => 1
	case Response(out) => 2
	case Error(err) => 2

	// low priority
	case EOF() => 4

	// last priority
	case PoisonPill => 5

	// mid priority
	case otherwise => 3
})

class BoundedReactiveMailbox(settings:ActorSystem.Settings, config:Config) extends BoundedStablePriorityMailbox(PriorityGenerator {
	// high priority
	case SetupWorker(mapperProps, nextTarget, num) => 0
	case Request(vf) => 1
	case Response(out) => 2
	case Error(err) => 2

	// low priority
	case EOF() => 4

	// last priority
	case PoisonPill => 5

	// mid priority
	case otherwise => 3
}, config.getInt("mailbox-capacity"), Duration(config.getString("mailbox-push-timeout-time")))