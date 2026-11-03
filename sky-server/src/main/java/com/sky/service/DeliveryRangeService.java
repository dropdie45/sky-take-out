package com.sky.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.entity.AddressBook;
import com.sky.exception.OrderBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;

/** 下单前通过百度地图校验配送路线距离。 */
@Service
public class DeliveryRangeService {
    private static final BigDecimal MAX_DISTANCE = BigDecimal.valueOf(5000);
    private final String shopAddress;
    private final String ak;
    private final RestTemplate restTemplate;

    public DeliveryRangeService(@Value("${sky.shop.address:}") String shopAddress,
                                @Value("${sky.baidu.ak:}") String ak) {
        this.shopAddress = shopAddress;
        this.ak = ak;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void check(AddressBook addressBook) {
        if (!StringUtils.hasText(shopAddress) || !StringUtils.hasText(ak)) {
            throw new OrderBusinessException("配送服务未配置，请联系商家设置门店地址和百度地图AK");
        }
        if (addressBook == null || !StringUtils.hasText(addressBook.getDetail())) {
            throw new OrderBusinessException("请完善收货地址");
        }
        String address = text(addressBook.getProvinceName()) + text(addressBook.getCityName())
                + text(addressBook.getDistrictName()) + addressBook.getDetail().trim();
        String origin = geocode(shopAddress, "门店地址解析失败，请联系商家");
        String destination = geocode(address, "收货地址解析失败，请检查地址");
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.map.baidu.com/directionlite/v1/driving")
                .queryParam("ak", ak).queryParam("origin", origin)
                .queryParam("destination", destination).queryParam("coord_type", "bd09ll")
                .queryParam("steps_info", "0").build().encode().toUri();
        JSONObject response = request(uri, "配送路线规划失败，请稍后重试");
        JSONObject result = response.getJSONObject("result");
        JSONArray routes = result == null ? null : result.getJSONArray("routes");
        if (routes == null || routes.isEmpty() || routes.getJSONObject(0) == null) {
            throw new OrderBusinessException("未找到可用配送路线，请检查收货地址");
        }
        BigDecimal distance;
        try {
            distance = routes.getJSONObject(0).getBigDecimal("distance");
        } catch (RuntimeException e) {
            throw new OrderBusinessException("配送距离数据异常，请稍后重试");
        }
        if (distance == null || distance.signum() < 0) {
            throw new OrderBusinessException("配送距离数据异常，请稍后重试");
        }
        if (distance.compareTo(MAX_DISTANCE) > 0) {
            throw new OrderBusinessException("超出配送范围，配送距离不能超过5公里");
        }
    }

    private String geocode(String address, String errorMessage) {
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.map.baidu.com/geocoding/v3/")
                .queryParam("address", address).queryParam("ak", ak)
                .queryParam("output", "json").queryParam("ret_coordtype", "bd09ll")
                .build().encode().toUri();
        JSONObject response = request(uri, errorMessage);
        try {
            JSONObject result = response.getJSONObject("result");
            JSONObject location = result == null ? null : result.getJSONObject("location");
            BigDecimal lat = location == null ? null : location.getBigDecimal("lat");
            BigDecimal lng = location == null ? null : location.getBigDecimal("lng");
            if (lat == null || lng == null || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                    || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new OrderBusinessException(errorMessage);
            }
            // 路线接口要求纬度在前、经度在后，小数点后最多六位。
            return lat.setScale(6, RoundingMode.HALF_UP).toPlainString() + ","
                    + lng.setScale(6, RoundingMode.HALF_UP).toPlainString();
        } catch (RuntimeException e) {
            throw new OrderBusinessException(errorMessage);
        }
    }

    private JSONObject request(URI uri, String errorMessage) {
        try {
            String body = restTemplate.getForObject(uri, String.class);
            JSONObject response = JSONObject.parseObject(body);
            if (response == null || !Integer.valueOf(0).equals(response.getInteger("status"))) {
                throw new OrderBusinessException(errorMessage);
            }
            return response;
        } catch (RuntimeException e) {
            // 不输出请求 URL 或原始异常，避免将 AK 和用户地址写入日志。
            throw new OrderBusinessException(errorMessage);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
