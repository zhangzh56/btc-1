package net.kadirderer.btc.service;

import java.util.List;

import net.kadirderer.btc.db.criteria.UserOrderCriteria;
import net.kadirderer.btc.db.model.FailedOrder;
import net.kadirderer.btc.db.model.UserOrder;
import net.kadirderer.btc.web.dto.DatatableAjaxResponse;

public interface UserOrderService {
	
	public List<UserOrder> findAllByUsername(String username);
	
	public List<UserOrder> findByCriteria(UserOrderCriteria criteria);
	
	public long findByCriteriaCount(UserOrderCriteria criteria);
	
	public FailedOrder findFailedOrder(int userOrderId);
	
	public FailedOrder saveFailedOrder(FailedOrder order);
	
	public DatatableAjaxResponse<UserOrder> query(UserOrderCriteria criteria);
	
	public UserOrder findUserOrder(int userOrderId);
	
	public void cancelOrder(String username, String orderId, boolean forUpdate) throws Exception;
}
