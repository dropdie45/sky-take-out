package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.DeliveryRangeService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private DeliveryRangeService deliveryRangeService;

    @Autowired
    private WebSocketServer webSocketServer;

    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) {
        if (ordersPaymentDTO == null || !Integer.valueOf(1).equals(ordersPaymentDTO.getPayMethod())) {
            throw new OrderBusinessException("请选择微信支付方式");
        }
        getPaymentOrder(ordersPaymentDTO.getOrderNumber());

        // 本地模拟支付：跳过微信预支付接口，不生成支付凭证、不发生真实扣款。
        // JSONObject jsonObject = weChatPayUtil.pay(orderNumber, amount, "苍穹外卖订单", openid);
        JSONObject jsonObject = new JSONObject();
        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        return vo;
    }

    @Override
    @Transactional
    public void paySuccess(String outTradeNo) {
        Orders order = getPaymentOrder(outTradeNo);
        if (Orders.PAID.equals(order.getPayStatus())) {
            return;
        }
        Orders update = Orders.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .payMethod(1)
                .checkoutTime(LocalDateTime.now())
                .build();
        if (orderMapper.markPaid(update) == 0) {
            // 并发重复请求若已经支付则直接成功，其他状态变化返回业务错误。
            Orders latest = getPaymentOrder(outTradeNo);
            if (!Orders.PAID.equals(latest.getPayStatus())) {
                throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
            }
            return;
        }
        // 提交后再通知，避免商家收到尚未落库或已回滚的订单。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendOrderNotification(1, order);
                }
            });
        } else {
            sendOrderNotification(1, order);
        }
    }

    @Override
    public void reminder(Long id) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new OrderBusinessException(MessageConstant.USER_NOT_LOGIN);
        }
        Orders order = id == null ? null : orderMapper.getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!Orders.PAID.equals(order.getPayStatus()) ||
                !(Orders.TO_BE_CONFIRMED.equals(order.getStatus()) ||
                  Orders.CONFIRMED.equals(order.getStatus()) ||
                  Orders.DELIVERY_IN_PROGRESS.equals(order.getStatus()))) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        sendOrderNotification(2, order);
    }

    private void sendOrderNotification(int type, Orders order) {
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("orderId", order.getId());
        message.put("content", "订单号：" + order.getNumber());
        webSocketServer.sendToAllClient(message.toJSONString());
    }

    private Orders getPaymentOrder(String orderNumber) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new OrderBusinessException(MessageConstant.USER_NOT_LOGIN);
        }
        if (!StringUtils.hasText(orderNumber)) {
            throw new OrderBusinessException("订单号不能为空");
        }
        Orders order = orderMapper.getByNumberAndUserId(orderNumber, userId);
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (Orders.PAID.equals(order.getPayStatus()) && !Orders.CANCELLED.equals(order.getStatus())) {
            return order;
        }
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        return order;
    }

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {

        // 处理各种业务异常(地址簿为空，购物车数据为空)
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook == null){
            //抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //查询当前用户的购物车数据
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0){
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        // 校验地址归属及配送范围，失败时不创建订单、不清空购物车。
        if (userId == null || !userId.equals(addressBook.getUserId())) {
            throw new AddressBookBusinessException("收货地址不存在或不属于当前用户");
        }
        deliveryRangeService.check(addressBook);
        //向订单表插入1条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        List<OrderDetail> orderDetailList = new ArrayList<>();
        //向订单明细表插入n条数据
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());//设置当前订单明细关联的订单ID
            orderDetailList.add(orderDetail);
        }

        orderDetailMapper.insertBatch(orderDetailList);
        //清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);
        //封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();
        return orderSubmitVO;
    }

    /**
     * 查询历史订单
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    @Override
    public PageResult historyOrders(Integer page, Integer pageSize, Integer status) {
        // 1. 构造查询条件：当前用户 + 可选订单状态
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 2. 开启分页，紧接着查询订单
        PageHelper.startPage(page, pageSize);
        Page<Orders> ordersPage = orderMapper.list(ordersPageQueryDTO);

        List<OrderVO> records = new ArrayList<>();
        for (Orders order : ordersPage){
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(order,orderVO);

            //查询订单明细
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(order.getId());
            orderVO.setOrderDetailList(orderDetailList);
            records.add(orderVO);
        }

        PageResult pageResult = new PageResult();
        pageResult.setRecords(records);
        pageResult.setTotal(ordersPage.getTotal());

        return pageResult;
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO getById(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getUserId().equals(BaseContext.getCurrentId())){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        //查询订单明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 取消订单
     * @param id
     */
    @Override
    public void cancel(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getUserId().equals(BaseContext.getCurrentId())){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (orders.getStatus() != Orders.PENDING_PAYMENT && orders.getStatus() != Orders.TO_BE_CONFIRMED){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        if (Orders.PAID.equals(orders.getPayStatus())) {
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null || !orders.getUserId().equals(BaseContext.getCurrentId())){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList){
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail,shoppingCart);
            shoppingCart.setId(null);
            shoppingCart.setUserId(BaseContext.getCurrentId());
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartList.add(shoppingCart);
        }
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> ordersPage = orderMapper.list(ordersPageQueryDTO);

        List<OrderVO> orderVO = new ArrayList<>();
        for (Orders orders : ordersPage){
            OrderVO orderVO1 = new OrderVO();
            BeanUtils.copyProperties(orders,orderVO1);
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

            StringBuilder stringBuilder =new StringBuilder();
            for (OrderDetail orderDetail : orderDetailList){
                stringBuilder.append(orderDetail.getName());
                stringBuilder.append("*");
                stringBuilder.append(orderDetail.getNumber());
                stringBuilder.append(";");
            }
            orderVO1.setOrderDishes(stringBuilder.toString());
            orderVO.add(orderVO1);
        }
        PageResult pageResult = new PageResult();
        pageResult.setTotal(ordersPage.getTotal());
        pageResult.setRecords(orderVO);
        return pageResult;
    }

    /**
     * 各状态订单数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        Integer toBeConfirmed = orderMapper.countByStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countByStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countByStatus(Orders.DELIVERY_IN_PROGRESS);

        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 获取订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = orderMapper.getById(ordersConfirmDTO.getId());
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (orders.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            orders.setStatus(Orders.CONFIRMED);
            orderMapper.update(orders);
        }else {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        Long id = ordersRejectionDTO.getId();
        Orders orders = orderMapper.getById(id);
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (orders.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            if (orders.getPayStatus().equals(Orders.PAID)){
                orders.setPayStatus(Orders.REFUND);
            }
            orders.setStatus(Orders.CANCELLED);
            orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
            orders.setCancelTime(LocalDateTime.now());
            orderMapper.update(orders);
        }else {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 商家取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void adminCancel(OrdersCancelDTO ordersCancelDTO) {
        Long id = ordersCancelDTO.getId();
        Orders orders = orderMapper.getById(id);
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (orders.getStatus().equals(Orders.COMPLETED) || orders.getStatus().equals(Orders.CANCELLED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if (orders.getPayStatus().equals(Orders.PAID)){
            orders.setPayStatus(Orders.REFUND);
        }
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void delivery(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!orders.getStatus().equals(Orders.CONFIRMED) ){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orderMapper.update(orders);
    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS) ){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(orders);
    }
}
