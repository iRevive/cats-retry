package retry.mtl

import cats.data.EitherT
import cats.instances.either._
import cats.mtl.instances.handle._
import org.scalatest.flatspec.AnyFlatSpec
import retry.{RetryDetails, RetryPolicies, Sleep}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PackageObjectSpec extends AnyFlatSpec {
  type ErrorOr[A] = Either[Throwable, A]
  type F[A]       = EitherT[ErrorOr, String, A]

  behavior of "retryingOnSomeErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[F] =
      new Sleep[F] {
        def sleep(delay: FiniteDuration): F[Unit] = EitherT.pure(())
      }

    val error  = new RuntimeException("Boom!")
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: Either[Throwable, String] => Boolean = {
      case Right(string) => string == "one more time"
      case Left(cause)   => cause == error
      case _             => false
    }

    val finalResult = retryingOnSomeErrors(policy, isWorthRetrying, onError) {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(!gaveUp)
  }

  it should "retry only if the error is worth retrying" in new TestContext {
    implicit val sleepForEither: Sleep[F] =
      new Sleep[F] {
        def sleep(delay: FiniteDuration): F[Unit] = EitherT.pure(())
      }

    val error  = new RuntimeException("Boom!")
    val policy = RetryPolicies.constantDelay[F](1.second)

    val isWorthRetrying: Either[Throwable, String] => Boolean = {
      case Right(string) => string == "one more time"
      case Left(cause)   => cause == error
      case _             => false
    }

    val finalResult = retryingOnSomeErrors(policy, isWorthRetrying, onError) {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("nope")
      }
    }

    assert(finalResult.value == Right(Left("nope")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(!gaveUp) // false because onError is only called when the error is worth retrying
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[F] =
      new Sleep[F] {
        def sleep(delay: FiniteDuration): F[Unit] = EitherT.pure(())
      }

    val error  = new RuntimeException("Boom!")
    val policy = RetryPolicies.limitRetries[F](2)

    val isWorthRetrying: Either[Throwable, String] => Boolean = {
      case Right(string) => string == "one more time"
      case Left(cause)   => cause == error
      case _             => false
    }

    val finalResult = retryingOnSomeErrors(policy, isWorthRetrying, onError) {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 3)
    assert(
      errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time")
      )
    )
    assert(gaveUp)
  }

  behavior of "retryingOnAllErrors"

  it should "retry until the action succeeds" in new TestContext {
    implicit val sleepForEither: Sleep[F] =
      new Sleep[F] {
        def sleep(delay: FiniteDuration): F[Unit] = EitherT.pure(())
      }

    val error  = new RuntimeException("Boom!")
    val policy = RetryPolicies.constantDelay[F](1.second)

    val finalResult = retryingOnAllErrors(policy, onError) {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.pure[ErrorOr, String]("yay")
      }
    }

    assert(finalResult.value == Right(Right("yay")))
    assert(attempts == 3)
    assert(errors.toList == List(Right("one more time"), Left(error)))
    assert(!gaveUp)
  }

  it should "retry until the policy chooses to give up" in new TestContext {
    implicit val sleepForEither: Sleep[F] =
      new Sleep[F] {
        def sleep(delay: FiniteDuration): F[Unit] = EitherT.pure(())
      }

    val error  = new RuntimeException("Boom!")
    val policy = RetryPolicies.limitRetries[F](2)

    val finalResult = retryingOnAllErrors(policy, onError) {
      attempts = attempts + 1

      attempts match {
        case 1 => EitherT.leftT[ErrorOr, String]("one more time")
        case 2 => EitherT[ErrorOr, String, String](Left(error))
        case _ => EitherT.leftT[ErrorOr, String]("one more time")
      }
    }

    assert(finalResult.value == Right(Left("one more time")))
    assert(attempts == 3)
    assert(
      errors.toList == List(
        Right("one more time"),
        Left(error),
        Right("one more time")
      )
    )
    assert(gaveUp)
  }

  private class TestContext {
    var attempts = 0
    val errors   = ArrayBuffer.empty[Either[Throwable, String]]
    val delays   = ArrayBuffer.empty[FiniteDuration]
    var gaveUp   = false

    def onError(
        error: Either[Throwable, String],
        details: RetryDetails
    ): F[Unit] = {
      errors.append(error)
      details match {
        case RetryDetails.WillDelayAndRetry(delay, _, _) => delays.append(delay)
        case RetryDetails.GivingUp(_, _)                 => gaveUp = true
      }
      EitherT.pure(())
    }
  }
}
