package com.example.stock.facade;

import com.example.stock.repository.RedisLockRepository;
import com.example.stock.service.StockService;
import org.springframework.stereotype.Component;

@Component
public class LettuceLockStockFacade {

    private final RedisLockRepository redisLockRepository;
    private final StockService stockService;

    public LettuceLockStockFacade(RedisLockRepository redisLockRepository, StockService stockService) {
        this.redisLockRepository = redisLockRepository;
        this.stockService = stockService;
    }

    /**
     * 로직 실행 전에 key와 setnx 명령어를 활용해서 락을 걸고
     * 로직이 끝나면 Unlock으로 락 해제
     */
    public void decrease(Long id, Long quantity) throws InterruptedException {
        // 락 획득 시도
        while (!redisLockRepository.lock(id)) {
            // 락을 획득하지 못한 경우 sleep 후 재시도
            Thread.sleep(100); // redis 부하 방지
        }

        // 락 획득에 성공하면 재고 감소
        try {
            stockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id); // 락 해제
        }
    }
}
