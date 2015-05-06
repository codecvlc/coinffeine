package coinffeine.model.market

import org.joda.time.DateTime

import coinffeine.model.ActivityLog
import coinffeine.model.currency._
import coinffeine.model.exchange._

/** An order represents a process initiated by a peer to bid (buy) or ask(sell) bitcoins in
  * the Coinffeine market.
  *
  * The peers of the Coinffeine network are there to exchange bitcoins and fiat money in a
  * decentralized way. When one peer wants to perform a buy or sell operation, he emits
  * an order. Objects of this class represent the state of an order.
  *
  * @constructor      Private constructor to keep invariants
  * @param orderType  The type of order (bid or ask)
  * @param amount     The gross amount of bitcoins to bid or ask
  * @param price      The price per bitcoin
  * @param inMarket   Presence on the order book
  * @param exchanges  The exchanges that have been initiated to complete this order
  * @param log        Log of important order events
  */
case class ActiveOrder[C <: FiatCurrency] private (
    id: OrderId,
    orderType: OrderType,
    amount: Bitcoin.Amount,
    price: OrderPrice[C],
    inMarket: Boolean,
    exchanges: Map[ExchangeId, Exchange[C]],
    log: ActivityLog[OrderStatus]) extends Order[C] {

  require(amount.isPositive, s"Orders should have a positive amount ($amount given)")

  def cancel(timestamp: DateTime): ActiveOrder[C] = copy(log = log.record(CancelledOrder, timestamp))
  def becomeInMarket: ActiveOrder[C] = copy(inMarket = true)
  def becomeOffline: ActiveOrder[C] = copy(inMarket = false)

  override def status: OrderStatus = log.mostRecent.get.event
  override def cancelled: Boolean = log.lastTime(_ == CancelledOrder).isDefined

  /** Create a new copy of this order with the given exchange. */
  def withExchange(exchange: Exchange[C]): ActiveOrder[C] =
    if (exchanges.get(exchange.id).contains(exchange)) this
    else {
      val nextExchanges = exchanges + (exchange.id -> exchange)
      val nextAmounts = ActiveOrder.Amounts.fromExchanges(amount, role, nextExchanges)
      val timestamp = exchange.log.mostRecent.get.timestamp

      def recordProgressStart(log: ActivityLog[OrderStatus]) =
        if (amounts.progressMade || !nextAmounts.progressMade) log
        else log.record(InProgressOrder, timestamp)

      def recordCompletion(log: ActivityLog[OrderStatus]) =
        if (amounts.completed || !nextAmounts.completed) log
        else log.record(CompletedOrder, timestamp)

      copy(
        inMarket = false,
        exchanges = nextExchanges,
        log = recordCompletion(recordProgressStart(log))
      )
    }

  /** Timestamp of the last recorded change */
  def lastChange: DateTime = log.mostRecent.get.timestamp

  override def createdOn = log.activities.head.timestamp
}

object ActiveOrder {
  case class Amounts(exchanged: Bitcoin.Amount,
                     exchanging: Bitcoin.Amount,
                     pending: Bitcoin.Amount) {
    require((exchanged + exchanging + pending).isPositive)
    def completed: Boolean = exchanging.isZero && pending.isZero
    def progressMade: Boolean = exchanging.isPositive || exchanged.isPositive
  }

  object Amounts {
    def fromExchanges[C <: FiatCurrency](amount: Bitcoin.Amount,
                                         role: Role,
                                         exchanges: Map[ExchangeId, Exchange[C]]): Amounts = {
      def totalSum(exchanges: Iterable[Exchange[C]]): Bitcoin.Amount =
        exchanges.map(ex => role.select(ex.amounts.exchangedBitcoin)).sum

      val exchangeGroups = exchanges.values.groupBy {
        case _: SuccessfulExchange[_] => 'exchanged
        case _: FailedExchange[_] => 'other
        case _ => 'exchanging
      }.mapValues(totalSum).withDefaultValue(Bitcoin.Zero)

      ActiveOrder.Amounts(
        exchanged = exchangeGroups('exchanged),
        exchanging = exchangeGroups('exchanging),
        pending = amount - exchangeGroups('exchanged) - exchangeGroups('exchanging)
      )
    }
  }

  def apply[C <: FiatCurrency](id: OrderId,
                               orderType: OrderType,
                               amount: Bitcoin.Amount,
                               price: Price[C],
                               timestamp: DateTime = DateTime.now()): ActiveOrder[C] =
    apply(id, orderType, amount, LimitPrice(price), timestamp)

  def apply[C <: FiatCurrency](id: OrderId,
                               orderType: OrderType,
                               amount: Bitcoin.Amount,
                               price: OrderPrice[C],
                               timestamp: DateTime): ActiveOrder[C] = {
    val log = ActivityLog(NotStartedOrder, timestamp)
    ActiveOrder(id, orderType, amount, price, inMarket = false, exchanges = Map.empty, log)
  }

  /** Creates a limit order with a random identifier. */
  def randomLimit[C <: FiatCurrency](orderType: OrderType,
                                     amount: Bitcoin.Amount,
                                     price: Price[C],
                                     timestamp: DateTime = DateTime.now()) =
    random(orderType, amount, LimitPrice(price), timestamp)

  /** Creates a market price order with a random identifier. */
  def randomMarketPrice[C <: FiatCurrency](orderType: OrderType,
                                           amount: Bitcoin.Amount,
                                           currency: C,
                                           timestamp: DateTime = DateTime.now()) =
    random(orderType, amount, MarketPrice(currency), timestamp)

  /** Creates a market price order with a random identifier. */
  def random[C <: FiatCurrency](orderType: OrderType,
                                amount: Bitcoin.Amount,
                                price: OrderPrice[C],
                                timestamp: DateTime = DateTime.now()) =
    ActiveOrder(OrderId.random(), orderType, amount, price, timestamp)
}