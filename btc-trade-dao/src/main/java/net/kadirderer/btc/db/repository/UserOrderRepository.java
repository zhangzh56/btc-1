package net.kadirderer.btc.db.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import net.kadirderer.btc.db.model.UserOrder;

public interface UserOrderRepository extends JpaRepository<UserOrder, Integer> {
	
	@Query("Select uo from UserOrder uo where uo.username = :username and "
			+ "uo.orderType = :ordertype and "
			+ "uo.platformId = :platformId and "
			+ "uo.createDate = (select max(createDate) "
				+ "from UserOrder "
				+ "where username = :username and "
					+ "status = 'P' and "
					+ "orderType = :ordertype and "
					+ "platformId = :platformId)")
	public UserOrder findLastPending(@Param("username") String username, @Param("platformId") int platformId, @Param("ordertype") char ordertype);	
	
	@Query("Select uo from UserOrder uo where uo.username = :username")
	public List<UserOrder> findByUsername(@Param("username") String username);
	
	@Query("Select uo from UserOrder uo where uo.username = :username")
	public Page<UserOrder> findByUsername(@Param("username") String username, Pageable page);
	
	@Query("Select uo from UserOrder uo where uo.username = :username and uo.status in ('P', 'N', 'S') and uo.platformId = :platformId")
	public List<UserOrder> findPending(@Param("username") String username, @Param("platformId") int platformId);
	
	@Query("Select uo from UserOrder uo where uo.username = :username and uo.status = 'P' and uo.platformId = :platformId")
	public Page<UserOrder> findPending(@Param("username") String username,
			@Param("platformId") int platformId, Pageable page);
	
	@Query("Select uo from UserOrder uo where uo.username = :username and "
			+ "uo.platformId = :platformId and uo.returnId = :orderId")
	public UserOrder findByOrderId(@Param("username") String username, 
			@Param("platformId") int platformId, @Param("orderId") String orderId);
	
	@Query("Update UserOrder set partnerId = :patnerUserOrderId where id = :userOrderId")
	@Modifying
	public void updatePartnerId(@Param("userOrderId") int userOrderId, 
			@Param("patnerUserOrderId") int partnerUserOrderId);
	
	@Query("Update UserOrder set partnerId = :newUserOrderId "
			+ "where partnerId = :oldUserOrderId and status = 'P'")
	@Modifying
	public void updatePartnerIdWithNewId(@Param("oldUserOrderId") int oldUserOrderId, 
			@Param("newUserOrderId") Integer newUserOrderId);
	
	@Query("Update UserOrder set status = :status "
			+ "where partnerId = :partnerUserOrderId and status = 'P'")
	@Modifying
	public void updatePendingPartnerStatus(@Param("partnerUserOrderId") int partnerUserOrderId, 
			@Param("status") char status);
	
	@Query("Select sum(amount) from UserOrder uo "
			+ "where uo.username = :username and uo.platformId = :platformId "
				+ "and uo.status in ('P', 'N', 'U') "
				+ "and uo.autoTrade = 1 and uo.autoUpdate = 1")
	public Double queryTotalPendingAutoUpdateOrderAmount(@Param("username") String username, 
			@Param("platformId") int platformId);
	
	@Query("Select sum(amount) "
			+ "from UserOrder uo "
			+ "where uo.username = :username and uo.platformId = :platformId "
				+ "and uo.status in ('P', 'U') "
				+ "and uo.autoTrade = 1 and uo.autoUpdate = 0")
	public Double queryTotalPendingNonUpdateOrderAmount(@Param("username") String username, 
			@Param("platformId") int platformId);

}
