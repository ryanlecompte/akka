/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.testkit.{ ImplicitSender, TestKit }
import org.scalatest.FunSuiteLike
import org.scalatest.matchers.ShouldMatchers

//#demo-code
import scala.collection._
import scala.concurrent.duration._
import scala.math.BigDecimal.int2bigDecimal

import akka.actor._
/**
 * Sample and test code for the aggregator patter.
 * This is based on Jamie Allen's tutorial at
 * http://jaxenter.com/tutorial-asynchronous-programming-with-akka-actors-46220.html
 */

sealed trait AccountType
case object Checking extends AccountType
case object Savings extends AccountType
case object MoneyMarket extends AccountType

case class GetCustomerAccountBalances(id: Long, accountTypes: Set[AccountType])
case class GetAccountBalances(id: Long)

case class AccountBalances(accountType: AccountType,
                           balance: Option[List[(Long, BigDecimal)]])

case class CheckingAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class SavingsAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class MoneyMarketAccountBalances(balances: Option[List[(Long, BigDecimal)]])

case object TimedOut
case object CantUnderstand

class SavingsAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! SavingsAccountBalances(Some(List((1, 150000), (2, 29000))))
  }
}
class CheckingAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! CheckingAccountBalances(Some(List((3, 15000))))
  }
}
class MoneyMarketAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! MoneyMarketAccountBalances(None)
  }
}

class AccountBalanceRetriever extends Actor with Aggregator {

  import context._

  //#initial-expect
  expectOnce {
    case GetCustomerAccountBalances(id, types) ⇒
      new AccountAggregator(sender, id, types)
    case _ ⇒
      sender ! CantUnderstand
      context.stop(self)
  }
  //#initial-expect

  class AccountAggregator(originalSender: ActorRef,
                          id: Long, types: Set[AccountType]) {

    val results =
      mutable.ArrayBuffer.empty[(AccountType, Option[List[(Long, BigDecimal)]])]

    if (types.size > 0)
      types foreach {
        case Checking    ⇒ fetchCheckingAccountsBalance()
        case Savings     ⇒ fetchSavingsAccountsBalance()
        case MoneyMarket ⇒ fetchMoneyMarketAccountsBalance()
      }
    else collectBalances() // Empty type list yields empty response

    context.system.scheduler.scheduleOnce(1.second, self, TimedOut)
    //#expect-timeout
    expect {
      case TimedOut ⇒ collectBalances(force = true)
    }
    //#expect-timeout

    //#expect-balance
    def fetchCheckingAccountsBalance() {
      context.actorOf(Props[CheckingAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case CheckingAccountBalances(balances) ⇒
          results += (Checking -> balances)
          collectBalances()
      }
    }
    //#expect-balance

    def fetchSavingsAccountsBalance() {
      context.actorOf(Props[SavingsAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case SavingsAccountBalances(balances) ⇒
          results += (Savings -> balances)
          collectBalances()
      }
    }

    def fetchMoneyMarketAccountsBalance() {
      context.actorOf(Props[MoneyMarketAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case MoneyMarketAccountBalances(balances) ⇒
          results += (MoneyMarket -> balances)
          collectBalances()
      }
    }

    def collectBalances(force: Boolean = false) {
      if (results.size == types.size || force) {
        originalSender ! results.toList // Make sure it becomes immutable
        context.stop(self)
      }
    }
  }
}
//#demo-code

//#chain-sample
case class InitialRequest(name: String)
case class Request(name: String)
case class Response(name: String, value: String)
case class EvaluationResults(name: String, eval: List[Int])
case class FinalResponse(qualifiedValues: List[String])

/**
 * An actor sample demonstrating use of unexpect and chaining.
 * This is just an example and not a complete test case.
 */
class ChainingSample extends Actor with Aggregator {

  expectOnce {
    case InitialRequest(name) ⇒ new MultipleResponseHandler(sender, name)
  }

  class MultipleResponseHandler(originalSender: ActorRef, propName: String) {

    import context.dispatcher
    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[String]

    context.actorSelection("/user/request_proxies") ! Request(propName)
    context.system.scheduler.scheduleOnce(50.milliseconds, self, TimedOut)

    //#unexpect-sample
    val handle = expect {
      case Response(name, value) ⇒
        values += value
        if (values.size > 3) processList()
      case TimedOut ⇒ processList()
    }

    def processList() {
      unexpect(handle)

      if (values.size > 0) {
        context.actorSelection("/user/evaluator") ! values.toList
        expectOnce {
          case EvaluationResults(name, eval) ⇒ processFinal(eval)
        }
      } else processFinal(List.empty[Int])
    }
    //#unexpect-sample

    def processFinal(eval: List[Int]) {
      // Select only the entries coming back from eval
      originalSender ! FinalResponse(eval map values)
      context.stop(self)
    }
  }
}
//#chain-sample

class AggregatorSpec extends TestKit(ActorSystem("test")) with ImplicitSender with FunSuiteLike with ShouldMatchers {

  test("Test request 1 account type") {
    system.actorOf(Props[AccountBalanceRetriever]) ! GetCustomerAccountBalances(1, Set(Savings))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 1
      case result ⇒
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }

  test("Test request 3 account types") {
    system.actorOf(Props[AccountBalanceRetriever]) !
      GetCustomerAccountBalances(1, Set(Checking, Savings, MoneyMarket))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 3
      case result ⇒
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }
}

case class TestEntry(id: Int)

class WorkMapSpec extends FunSuiteLike {

  val workMap = WorkMap.empty[TestEntry]
  var entry2: TestEntry = null
  var entry4: TestEntry = null

  test("Processing empty WorkMap") {
    // ProcessAndRemove something in the middle
    val processed = workMap process {
      case TestEntry(9) ⇒ true
      case _            ⇒ false
    }
    assert(!processed)
  }

  test("Process temp entries") {
    val entry0 = TestEntry(0)
    workMap.add(entry0, permanent = false)
    val entry1 = TestEntry(1)
    workMap.add(entry1, permanent = false)
    entry2 = TestEntry(2)
    workMap.add(entry2, permanent = false)
    val entry3 = TestEntry(3)
    workMap.add(entry3, permanent = false)

    // ProcessAndRemove something in the middle
    assert(workMap process {
      case TestEntry(2) ⇒ true
      case _            ⇒ false
    })

    // ProcessAndRemove the head
    assert(workMap process {
      case TestEntry(0) ⇒ true
      case _            ⇒ false
    })

    // ProcessAndRemove the tail
    assert(workMap process {
      case TestEntry(3) ⇒ true
      case _            ⇒ false
    })
  }

  test("Process permanent entry") {
    entry4 = TestEntry(4)
    workMap.add(entry4, permanent = true)
    assert(workMap process {
      case TestEntry(4) ⇒ true
      case _            ⇒ false
    })
  }

  test("Remove permanent entry") {
    val removed = workMap remove entry4
    assert(removed)
  }

  test("Remove temp entry already processed") {
    val removed = workMap remove entry2
    assert(!removed)
  }

  test("Process non-matching entries") {
    val processed =
      workMap process {
        case TestEntry(2) ⇒ true
        case _            ⇒ false
      }

    assert(!processed)

    val processed2 =
      workMap process {
        case TestEntry(5) ⇒ true
        case _            ⇒ false
      }

    assert(!processed2)

  }

  test("Append two work maps") {
    workMap.removeAll()
    0 to 4 foreach { id ⇒ workMap.add(TestEntry(id), permanent = false) }

    val l2 = new WorkMap[TestEntry]
    5 to 9 foreach { id ⇒ l2.add(TestEntry(id), permanent = true) }

    workMap addAll l2

    assert(workMap.size === 10)
  }

  test("Clear work map") {
    workMap.removeAll()
    assert(workMap.size === 0)
  }

  val workMap2 = WorkMap.empty[PartialFunction[Any, Unit]]

  val fn1: PartialFunction[Any, Unit] = {
    case s: String ⇒
      val result1 = workMap2 remove fn1
      assert(result1 === true, "First remove must return true")
      val result2 = workMap2 remove fn1
      assert(result2 === false, "Second remove must return false")
  }

  val fn2: PartialFunction[Any, Unit] = {
    case s: String ⇒
      workMap2.add(fn1, permanent = true)
  }

  test("Reentrant insert") {
    workMap2.add(fn2, permanent = false)
    assert(workMap2.size === 1)

    // Processing inserted fn1, reentrant adding fn2
    workMap2 process { fn ⇒
      var processed = true
      fn.applyOrElse("Foo", (_: Any) ⇒ processed = false)
      processed
    }
  }

  test("Reentrant delete") {
    // Processing inserted fn2, should delete itself
    workMap2 process { fn ⇒
      var processed = true
      fn.applyOrElse("Foo", (_: Any) ⇒ processed = false)
      processed
    }
  }
}
