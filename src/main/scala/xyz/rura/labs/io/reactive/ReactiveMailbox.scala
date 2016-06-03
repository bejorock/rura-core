package xyz.rura.labs.io.reactive

import akka.dispatch.PriorityGenerator
import akka.dispatch.UnboundedStablePriorityMailbox
import akka.actor.ActorSystem
import akka.actor.PoisonPill

import com.typesafe.config.Config

import xyz.rura.labs.io._

import ReactiveStream.{Request, Response, EOF, SetupWorker}

class ReactiveMailbox(settings:ActorSystem.Settings, config:Config) extends UnboundedStablePriorityMailbox(PriorityGenerator {
	// high priority
	case SetupWorker(mapper, nextTarget, num) => 0
	case Request(vf) => 1
	case Response(out) => 2

	// low priority
	case EOF() => 4

	// last priority
	case PoisonPill => 5

	// mid priority
	case otherwise => 3
})