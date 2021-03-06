package net.kadirderer.btc.handler;

import java.util.Calendar;
import java.util.List;

import net.kadirderer.btc.api.updateorder.UpdateOrderResult;
import net.kadirderer.btc.db.model.Statistics;
import net.kadirderer.btc.db.model.UserOrder;
import net.kadirderer.btc.impl.buyorder.BtcChinaBuyOrderResult;
import net.kadirderer.btc.impl.cancelorder.BtcChinaCancelOrderResult;
import net.kadirderer.btc.impl.sellorder.BtcChinaSellOrderResult;
import net.kadirderer.btc.impl.updateorder.BtcChinaUpdateOrderResult;
import net.kadirderer.btc.impl.util.NumberUtil;
import net.kadirderer.btc.impl.util.PriceAnalyzer;
import net.kadirderer.btc.service.AutoTradeService;
import net.kadirderer.btc.util.StringUtil;
import net.kadirderer.btc.util.configuration.ConfigurationService;
import net.kadirderer.btc.util.email.Email;
import net.kadirderer.btc.util.enumaration.OrderStatus;
import net.kadirderer.btc.util.enumaration.OrderType;

public class OrderEvoluateHandler implements Runnable {
	
	private AutoTradeService autoTradeService;
	private ConfigurationService cfgService;
	private int userOrderId;
	private boolean processing = false;
	
	public static OrderEvoluateHandler evoluate(AutoTradeService autoTradeService, int userOrderId,ConfigurationService cfgService) {
		OrderEvoluateHandler handler = new OrderEvoluateHandler();
		handler.autoTradeService = autoTradeService;
		handler.userOrderId = userOrderId;
		handler.cfgService = cfgService;

		Thread thread = new Thread(handler);
		thread.start();
		
		return handler;
	}
	
	private OrderEvoluateHandler() {
		
	}

	@Override
	public void run() {
		processing = true;
		try {
			UserOrder uo = autoTradeService.findUserOrderById(userOrderId);
			
			if (uo.getCompletedAmount() > 0 && uo.getCompletedAmount() < uo.getAmount()) {
				processing = false;
				return;
			}
			
			double[] maxAndGeometricMeanArray = autoTradeService.getMaxAndGeometricMean(uo.getUsername());
			double highestBid = maxAndGeometricMeanArray[2];
			double gmob = maxAndGeometricMeanArray[3];
			double gmoa = maxAndGeometricMeanArray[1];
			double lowestAsk = maxAndGeometricMeanArray[0];
			
			if (uo.getStatus() == OrderStatus.PENDING.getCode() &&
					(uo.getOrderType() == OrderType.SELL.getCode() ||
							(!uo.isAutoUpdate() && uo.getOrderType() == OrderType.BUY.getCode()))) {				
				double priceHighestBidDiff = uo.getPrice() - highestBid;
				double lastBidPriceCheckDelta = cfgService.getLastBidPriceCheckDelta();
				
				if (uo.getOrderType() == OrderType.BUY.getCode()) {
					priceHighestBidDiff = highestBid - uo.getPrice();
				}
				
				double dailyHigh = autoTradeService.get24HoursHigh();
				
				UpdateOrderResult result = null;
				boolean isThisTheTime = isThisTheTime(uo, gmob, gmoa, highestBid, lowestAsk, dailyHigh);
				
				if (isThisTheTime) {
					double price = highestBid + (lowestAsk - highestBid) / 2.0;
					double amount = uo.getAmount();
					
					if (uo.getOrderType() == OrderType.BUY.getCode()) {
						boolean amountCalculated = false;
						if (uo.getParentId() != null) {
							UserOrder parent = autoTradeService.findUserOrderById(uo.getParentId());
							if (parent != null) {
								double balance = parent.getPrice() * parent.getAmount();
								amount = balance / price;
								amountCalculated = true;
							}
						}
						
						if (!amountCalculated) {
							amount = (uo.getPrice() * amount) / price;
						}						
					}					
					
					result = autoTradeService.updateOrder(uo, amount, price);
				}				
				else if (lastBidPriceCheckDelta >= priceHighestBidDiff) {
					double price = uo.getPrice();
					double amount = uo.getAmount();
					
					if (uo.getOrderType() == OrderType.BUY.getCode()) {
						price = price - cfgService.getBuyOrderDelta();
						amount = (uo.getPrice() * amount) / price;
					}
					else {
						price = price + cfgService.getSellOrderDelta();
					}
					 
					result = autoTradeService.updateOrder(uo, amount, price);
				}
				else if (uo.getOrderType() == OrderType.BUY.getCode() && uo.isAutoUpdate() && 
						isTimeOut(uo) && gmob > uo.getTarget()) {
					List<Statistics> latestStatistics = autoTradeService.findLastStatistics(cfgService.getOrderEvoluaterBoCheckLastGmob());
					PriceAnalyzer pa = new PriceAnalyzer(latestStatistics, cfgService.getOrderEvoluaterBoPriceAnalyzerPercentage());
					
					if (pa.isPriceIncreasing()) {
						autoTradeService.cancelOrder(uo.getUsername(), uo.getReturnId(), false);
						uo.setStatus(OrderStatus.CANCELLED.getCode());
					}
				}
				
				int checkLastGmobCount = cfgService.getCheckLastGmobCountBuyOrder();
				if (uo.getOrderType() == OrderType.SELL.getCode()) {
					checkLastGmobCount = cfgService.getCheckLastGmobCountSellOrder();
				}
				uo.addGmob(gmob, checkLastGmobCount);
				uo.addGmoa(gmoa, checkLastGmobCount);
				
				if (uo.getHighestGmob() == null || gmob > uo.getHighestGmob()) {
					uo.setHighestGmob(gmob);
				}
				
				autoTradeService.saveUserOrder(uo);
				
				if (result != null) {
					evoluateUpdateOrderResult((BtcChinaUpdateOrderResult)result, uo);
				}
			}
			else if (uo.getStatus() == OrderStatus.PENDING.getCode() &&
					(uo.isAutoTrade() && uo.getOrderType() == OrderType.SELL.getCode())) {
				int checkLastGmobCount = cfgService.getCheckLastGmobCountSellOrder();
				uo.addGmob(gmob, checkLastGmobCount);
				uo.addGmoa(gmoa, checkLastGmobCount);
				
				if (uo.getHighestGmob() == null || gmob > uo.getHighestGmob()) {
					uo.setHighestGmob(gmob);
				}
				
				autoTradeService.saveUserOrder(uo);
			}
			else if (uo.isAutoTrade() && uo.getStatus() == OrderStatus.DONE.getCode()) {
				createOrderForDoneOrder(uo, highestBid);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		processing = false;
	}
	
	private boolean isThisTheTime(UserOrder uo, double gmob, double gmoa,
			double highestBid, double lowestAsk, double dailyHigh) {		
		
		List<Statistics> statisticsList = null;
		PriceAnalyzer pa = null;
		
		if (uo.getOrderType() == OrderType.BUY.getCode()) {
			int statisticsCount = cfgService.getOrderEvoluaterBoCheckLastGmob();
			int paPercentage = cfgService.getOrderEvoluaterBoPriceAnalyzerPercentage();
			
			if (!uo.isAutoUpdate()) {
				statisticsCount = cfgService.getOrderEvoluaterSoCheckLastGmob();
				paPercentage = cfgService.getOrderEvoluaterSoPriceAnalyzerPercentage();
			}
			
			statisticsList = autoTradeService.findLastStatistics(statisticsCount);
			pa = new PriceAnalyzer(statisticsList, paPercentage);
			
			if (highestBid + (lowestAsk - highestBid) / 2.0 <= uo.getTarget() && pa.isPriceIncreasing()) {
				return true;
			}
			
			return false;
		}
		
		if (uo.getOrderType() == OrderType.SELL.getCode() && uo.isAutoTrade() && !uo.isAutoUpdate()) {
			statisticsList = autoTradeService.findLastStatistics(cfgService.getOrderEvoluaterSoCheckLastGmob());
			pa = new PriceAnalyzer(statisticsList, cfgService.getOrderEvoluaterSoPriceAnalyzerPercentage());
			
			if (highestBid + (lowestAsk - highestBid) / 2.0 >= uo.getTarget() && !pa.isPriceIncreasing()) {
				return true;
			}
			
			return false;
		}
		
		if (uo.getOrderType() == OrderType.SELL.getCode() && cfgService.isUsePriceAnalyzerForSellOrder()) {			
			statisticsList = autoTradeService.findLastStatistics(cfgService.getOrderEvoluaterSoCheckLastGmob());
			pa = new PriceAnalyzer(statisticsList, cfgService.getOrderEvoluaterSoPriceAnalyzerPercentage());
			
			if (pa.getLastGmob() < pa.getPreviosGmob() && highestBid + (lowestAsk - highestBid) / 2.0 >= uo.getBasePrice()) {
				return true;
			}
			
			return false;
		}		
		
		String[] lastGmobArray = StringUtil.generateArrayFromDeliminatedString('|', uo.getLastGmobArray());
		if (lastGmobArray == null) {
			return false;
		}
		
		String[] lastGmoaArray = StringUtil.generateArrayFromDeliminatedString('|', uo.getLastGmoaArray());
		if (lastGmoaArray == null) {
			return false;
		}
		
		Double lastGmob = NumberUtil.parse(lastGmobArray[0]);		
		if (lastGmob == null) {
			return false;
		}
		
		if (uo.getHighestGmob() != null && uo.getOrderType() == OrderType.BUY.getCode() &&
				gmob > uo.getHighestGmob()) {
			return false;
		}
		
		if (uo.getOrderType() == OrderType.SELL.getCode() &&
				cfgService.getSellOrderLowerBufferStart() > 0.0 &&
				uo.getBasePrice() - lowestAsk > cfgService.getSellOrderLowerBufferStart() &&				
				uo.getBasePrice() - lowestAsk < cfgService.getSellOrderLowerBufferEnd() &&
				gmob < lastGmob) {
			
			if (lastGmobArray.length >= 2) {
				Double first = NumberUtil.parse(lastGmobArray[1]);
				
				if (first == null || first < lastGmob) {
					return false;
				}
			}
			
			return true;
		}
		
		double profit = (highestBid + (lowestAsk - highestBid) / 2.0) - uo.getBasePrice();
		boolean nonProfitAllowed = cfgService.isNonProfitSellOrderAllowed();
		boolean nonProfitAllowedIfParentHasProfit = cfgService.isNonProfitSellOrderAllowedIfParentHasProfit();
		if (uo.getOrderType() == OrderType.BUY.getCode()) {
			profit = uo.getBasePrice() - (highestBid + (lowestAsk - highestBid) / 2.0);
			nonProfitAllowed = cfgService.isNonProfitBuyOrderAllowed();
			nonProfitAllowedIfParentHasProfit = cfgService.isNonProfitBuyOrderAllowedIfParentHasProfit();
		}	
		
		if (profit < 0.0 && uo.getParentId() != null && nonProfitAllowedIfParentHasProfit) {
			UserOrder parent = autoTradeService.findUserOrderById(uo.getParentId());				
			if (parent != null) {
				double parentProfit = 0.0;
				if (parent.getOrderType() == OrderType.BUY.getCode()) {
					parentProfit = parent.getBasePrice() - parent.getPrice();
				}						
				else if (parent.getOrderType() == OrderType.SELL.getCode()) {
					parentProfit = parent.getPrice() - parent.getBasePrice();
				}
				
				if (parentProfit < 0.0 || -1.0 * profit > parentProfit) {
					return false;
				}
			}
		}
		
		if (profit < 0.0 && uo.getParentId() != null && !nonProfitAllowedIfParentHasProfit && !nonProfitAllowed) {
			return false;
		}
		
		try {						
			if (uo.getOrderType() == OrderType.SELL.getCode()) {				
				if (gmob >= lastGmob) {
					return false;
				}
				else if (lastGmobArray.length >= 2 && profit > cfgService.getAutoTradeSellOrderDecreaseBuffer()) {
					Double first = NumberUtil.parse(lastGmobArray[1]);
					
					if (first == null || first < lastGmob) {
						return false;
					}
				}
			}
			else if (uo.getOrderType() == OrderType.BUY.getCode()) {				
				if (gmob < lastGmob) {
					return false;
				}
				
				if ((highestBid + (lowestAsk - highestBid) / 2.0) >= dailyHigh - cfgService.getAutoUpdateRange()) {
					return false;
				}
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}						
		
		return false;
	}	
	
	private boolean isTimeOut(UserOrder userOrder) {
		long timeInMillis = Calendar.getInstance().getTimeInMillis();
		long elapsedTime = timeInMillis - userOrder.getCreateDate().getTime();
		
		int timelimit = cfgService.getBuyOrderTimeLimit() * 1000;
		
		if (!userOrder.isAutoUpdate()) {
			timelimit = cfgService.getNonAutoUpdateTimeLimit() * 1000;
		}
		
		if (elapsedTime < timelimit) {
			return false;
		}
		
		return true;
	}
	
	@SuppressWarnings("unused")
	private boolean canOrderCounterpart(UserOrder order, double highestBid) {
		if (order.getParentId() == null) {
			return false;
		}
		
		UserOrder parent = autoTradeService.findUserOrderById(order.getParentId());
		
		if (order.getOrderType() == OrderType.BUY.getCode() && 
				(parent == null || parent.getStatus() != OrderStatus.CANCELLED.getCode())) {
			return false;
		}
		else if (order.getOrderType() == OrderType.BUY.getCode() && 
				(parent != null && parent.getStatus() == OrderStatus.CANCELLED.getCode())) {
			parent.setPrice(highestBid);
			parent.setAutoTrade(true);
			parent.setAutoUpdate(true);
		}
		else if (order.getOrderType() == OrderType.SELL.getCode() && 
				(parent == null || parent.getStatus() != OrderStatus.CANCELLED.getCode())) {
			parent = new UserOrder();
			parent.setUsername(order.getUsername());
			parent.setAutoTrade(true);
			parent.setAutoUpdate(true);
			parent.setOrderType(OrderType.BUY.getCode());
			parent.setBasePrice(highestBid);
			parent.setPrice(highestBid - cfgService.getBuyOrderDelta());
			parent.setAmount(order.getAmount());
			parent.setParentId(order.getId());
		}
		else if (order.getOrderType() == OrderType.SELL.getCode() &&
				(parent != null && parent.getStatus() == OrderStatus.CANCELLED.getCode())) {
			parent.setPrice(highestBid  - cfgService.getBuyOrderDelta());
			parent.setAmount((parent.getAmount() * parent.getPrice()) / (highestBid - cfgService.getBuyOrderDelta()));
			parent.setAutoTrade(true);
			parent.setAutoUpdate(true);
		}
		
		try {
			if (order.getOrderType() == OrderType.BUY.getCode()) {
				BtcChinaSellOrderResult buyResult = (BtcChinaSellOrderResult)autoTradeService.sellOrder(parent);
				
				if (buyResult.getError() != null) {
					return false;
				}
			}
			else {
				BtcChinaBuyOrderResult buyResult = (BtcChinaBuyOrderResult)autoTradeService.buyOrder(parent);
				if (buyResult.getError() != null) {
					return false;
				}
			}
			
			BtcChinaCancelOrderResult cancelResult = (BtcChinaCancelOrderResult)autoTradeService.cancelOrder(
					order.getUsername(), order.getReturnId(), false);
			
			if (cancelResult.getError() != null) {
				return false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		
		return true;		
	}
	
	@SuppressWarnings("unused")
	private void createOrderForNonAutoUpdateTimeOut(UserOrder userOrder) throws Exception {		
		double highestBid = autoTradeService.getHighestBid();
		double lowestAsk = autoTradeService.getLowestAsk();
		
		if (userOrder.getOrderType() == OrderType.BUY.getCode()) {
			try {
				autoTradeService.cancelOrder(userOrder.getUsername(), userOrder.getReturnId(), false);
				
				double oldCost = userOrder.getPrice() * userOrder.getAmount();
				double price = lowestAsk - cfgService.getNonAutoUpdateOrderDelta();
				
				double amount = oldCost / price;
				
				Thread.sleep(cfgService.getWaitTimeAfterCancelBuyOrder() * 1000);
				
				UserOrder order = new UserOrder();
				order.setUsername(userOrder.getUsername());
				order.setBasePrice(userOrder.getBasePrice());
				order.setParentId(userOrder.getParentId());
				order.setPrice(price);
				order.setAmount(amount);
				order.setAutoUpdate(userOrder.isAutoUpdate());
				order.setAutoTrade(userOrder.isAutoTrade());
				
				BtcChinaBuyOrderResult result = (BtcChinaBuyOrderResult)autoTradeService.buyOrder(order);							
				evoluateBuyOrderResult(result, order);		
			} catch (Exception e) {
				autoTradeService.sendMailForException(e);
			}			
		} else {
			try {
				autoTradeService.cancelOrder(userOrder.getUsername(), userOrder.getReturnId(), false);
				double price = highestBid + cfgService.getNonAutoUpdateOrderDelta();
				
				Thread.sleep(cfgService.getWaitTimeAfterCancelSellOrder() * 1000);
				
				UserOrder order = new UserOrder();
				order.setUsername(userOrder.getUsername());
				order.setBasePrice(userOrder.getBasePrice());
				order.setParentId(userOrder.getParentId());
				order.setPrice(price);
				order.setAmount(userOrder.getAmount());
				order.setAutoUpdate(userOrder.isAutoUpdate());
				order.setAutoTrade(userOrder.isAutoTrade());
				
				BtcChinaSellOrderResult result = (BtcChinaSellOrderResult)autoTradeService.sellOrder(order);
				evoluateSellOrderResult(result, order);
			} catch (Exception e) {
				autoTradeService.sendMailForException(e);
			}
		}
	}
	
	private void createOrderForDoneOrder(UserOrder userOrder, double highestBid) throws Exception {
		double amount = userOrder.getAmount();
		
		if (userOrder.getOrderType() == OrderType.BUY.getCode()) {
			double price = userOrder.getPrice() + cfgService.getSellOrderDelta();			
			
			UserOrder order = new UserOrder();
			try {				
				order.setUsername(userOrder.getUsername());
				order.setBasePrice(userOrder.getPrice());
				order.setParentId(userOrder.getId());
				order.setPrice(price);
				order.setAmount(amount - (amount * 3D / 1000D));
				order.setAutoUpdate(userOrder.isAutoUpdate());
				order.setAutoTrade(userOrder.isAutoTrade());
				
				if (userOrder.isAutoTrade() && userOrder.isAutoUpdate()) {
					order.setStatus(OrderStatus.NEW.getCode());
				} 
				else if (userOrder.isAutoTrade() && !userOrder.isAutoUpdate()) {
					double target = userOrder.getAmount() * userOrder.getPrice();
					target = target / (userOrder.getAmount() - cfgService.getNonAutoUpdateSoReorderDelta());
					
					order.setTarget(target);
					order.setPrice(target + cfgService.getSellOrderDelta());
				}
				
				autoTradeService.sellOrder(order);				
			} catch (Exception e) {
				e.printStackTrace();
				Thread.sleep(cfgService.getWaitTimeAfterCancelBuyOrder() * 1000);
				order.setId(null);
				autoTradeService.sellOrder(order);
			}
		}
		else if (userOrder.getOrderType() == OrderType.SELL.getCode() && userOrder.isAutoTrade() &&
				!userOrder.isAutoUpdate()) {
			double spent = userOrder.getPrice() * userOrder.getAmount();
			double balance = spent - (spent * 3D / 1000D);
			amount = userOrder.getAmount() + cfgService.getNonAutoUpdateBoReorderDelta();
			double target = balance / amount;
			
			UserOrder order = new UserOrder();
			try {				
				order.setUsername(userOrder.getUsername());
				order.setBasePrice(userOrder.getPrice());
				order.setParentId(userOrder.getId());
				order.setPrice(target - cfgService.getBuyOrderDelta());
				order.setAmount(balance / order.getPrice());
				order.setAutoUpdate(userOrder.isAutoUpdate());
				order.setAutoTrade(userOrder.isAutoTrade());
				order.setTarget(target);
				
				autoTradeService.buyOrder(order);				
			} catch (Exception e) {
				e.printStackTrace();
				Thread.sleep(cfgService.getWaitTimeAfterCancelSellOrder() * 1000);
				order.setId(null);
				autoTradeService.buyOrder(order);
			}
		}
	}
	
	private void evoluateBuyOrderResult(BtcChinaBuyOrderResult result, UserOrder order) {
		if (result.getError() != null) {
			Email email = new Email();
			email.addToToList("kderer@hotmail.com");
			email.setSubject("BTC Buy Order Error");
			email.setFrom("error@btc.kadirderer.net");
			email.setBody(result.getError().getCode() + "\n" + result.getError().getMessage());		
			
			try {
				autoTradeService.sendMail(email);
				Thread.sleep(cfgService.getWaitTimeAfterCancelBuyOrder() * 1000);
				order.setId(null);
				if (order.isAutoTrade() && order.isAutoUpdate()) {
					order.setStatus(OrderStatus.NEW.getCode());
				}
				autoTradeService.buyOrder(order);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void evoluateSellOrderResult(BtcChinaSellOrderResult result, UserOrder order) {
		if (result.getError() != null) {
			Email email = new Email();
			email.addToToList("kderer@hotmail.com");
			email.setSubject("BTC Sell Order Error");
			email.setFrom("error@btc.kadirderer.net");
			email.setBody(result.getError().getCode() + "\n" + result.getError().getMessage());		
			
			try {
				autoTradeService.sendMail(email);
				Thread.sleep(cfgService.getWaitTimeAfterCancelSellOrder() * 1000);
				order.setId(null);
				autoTradeService.sellOrder(order);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void evoluateUpdateOrderResult(BtcChinaUpdateOrderResult result, UserOrder order) {
		if (result.getError() != null) {
					
		}
	}
	
	public boolean isProcessing() {
		return processing;
	}
}
