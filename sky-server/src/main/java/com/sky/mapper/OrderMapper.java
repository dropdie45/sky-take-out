package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;


@Mapper
public interface OrderMapper {
    @Select("select * from orders where number = #{number} and user_id = #{userId}")
    Orders getByNumberAndUserId(@Param("number") String number, @Param("userId") Long userId);

    /** 条件更新避免重复支付或覆盖已取消订单。 */
    @Update("update orders set status = #{status}, pay_status = #{payStatus}, " +
            "pay_method = #{payMethod}, checkout_time = #{checkoutTime} " +
            "where id = #{id} and user_id = #{userId} and status = 1 and pay_status = 0")
    int markPaid(Orders orders);

    /**
     * 插入订单数据
     * @param orders
     */
    void insert(Orders orders);


    /**
     *  查询历史订单
     * @param ordersPageQueryDTO
     */
    Page<Orders> list(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 更新订单
     * @param orders
     */
    void update(Orders orders);

    /**
     * 各状态订单数量统计
     * @param status
     * @return
     */
    @Select("select count(*) from orders where  status = #{status}")
    Integer countByStatus(Integer status);

    /**
     * 根据订单状态和下单时间查询订单
     * @param status
     * @param orderTime
     * @return
     */
    @Select("SELECT * FROM orders WHERE status = #{status} and order_time < #{orderTIme}")
    List<Orders> getByStatusAndOrderTime(Integer status, LocalDateTime orderTime);


}
