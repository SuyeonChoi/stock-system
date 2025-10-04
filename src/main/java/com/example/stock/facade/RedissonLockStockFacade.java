package com.example.stock.facade;

import com.example.stock.service.StockService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RedissonLockStockFacade {

    private RedissonClient redissonClient;

    private StockService stockService;

    public RedissonLockStockFacade(RedissonClient redissonClient, StockService stockService) {
        this.redissonClient = redissonClient;
        this.stockService = stockService;
    }

    public void decrease(Long id, Long quantity) {
        // 락 객체 가져오기
        RLock lock = redissonClient.getLock(id.toString());

        try {

            boolean available = lock.tryLock(10, 1, TimeUnit.SECONDS);// 몇 초동안 락 획득을 시도할 것인지, 점유할 것인지 설정

            if (!available) {
                // 락 획득에 실패하면 로그 남기고 리턴
                System.out.println("lock 획득 실패");
                return;
            }

            // 락을 획득한 경우 재고 감소
            stockService.decrease(id, quantity);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock(); // 로직이 정상적으로 종료되면 락 해제
        }
    }
}
