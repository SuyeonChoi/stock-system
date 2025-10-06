# 재고 시스템과 동시성이슈
> 인프런 강의 [재고시스템으로 알아보는 동시성이슈 해결방법](https://www.inflearn.com/course/%EB%8F%99%EC%8B%9C%EC%84%B1%EC%9D%B4%EC%8A%88-%EC%9E%AC%EA%B3%A0%EC%8B%9C%EC%8A%A4%ED%85%9C/dashboard)를 듣고 정리한 내용

## 재고 시스템
[기본 재고 감소 로직](https://github.com/SuyeonChoi/stock-system/commit/79fb01b3869f71b9c2bb1fc27deafe35f67a01a6)
```java
@Service
public class StockService {

    private final StockRepository stockRepository;

    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional
    public void decrease(Long id, Long quantity)  {
        // 1. Stock 조회
        Stock stock = stockRepository.findById(id).orElseThrow();
        // 2. 재고 감소
        stock.decrease(quantity);
        // 3. 갱신된 값 저장
        stockRepository.saveAndFlush(stock);
    }
}
```

### 문제
기본 재고 감소 로직에서 요청이 여러개가 들어오는 경우, 아래의 [멀티 스레드 테스트](https://github.com/SuyeonChoi/stock-system/commits/main/)는 실패한다
```java
  @Test
  public void 동시에_100개의_요청() throws InterruptedException {
      // 동시에 여러개의 요청을 보내기 위해 멀티 스레드 사용
      int threadCount = 100; // 요청 수

      // ExecutorService는 비동기로 실행하는 작업을 단순화하하여 사용할 수 있게 도와주는 Java API
      ExecutorService executorService = Executors.newFixedThreadPool(32);

      // 100개의 요청이 끝날 때까지 기다리기 위해 CountDownLatch 활용
      // 다른 스레드에서 수행중인 작업이 대기할 수 있도록 도와주는 클래스
      CountDownLatch latch = new CountDownLatch(threadCount);

      for (int i = 0; i < threadCount; i++) {
          executorService.submit(() -> {
              try {
                  stockService.decrease(1L, 1L);
              } finally {
                  latch.countDown();
              }
          });
      }

      latch.await();

      Stock stock = stockRepository.findById(1L).orElseThrow();
      // 100 - (1 * 100) = 0
      assertEquals(0, stock.getQuantity());
  }
```
테스트 결과
```
Expected :0
Actual   :89
```
### 테스트가 실패한 이유
- **Race Condition**이 발생 - 둘 이상의 스레드가 공유 데이터에 접근할 수 있고, 동시에 변경을 하려고 할때 발생하는 문제
- Expected: 스레드1이 데이터를 가져가서 갱신한 값을 스레드2가 가져가서 갱신하는 것을 기대

    <img src="https://github.com/user-attachments/assets/9fb43985-a5b4-48ae-a688-368cb4b94cdd" width="600" height="500"/>
    
- Actual: 스레드1이 데이터를 갱신을 하기 이전에 스레드2가 데이터를 조회. 결국 스레드1,2 모두 재고를 하나씩 줄이기 때문에 갱신이 누락

    <img src="https://github.com/user-attachments/assets/538643eb-71ec-453a-b9d2-1cfface33f56" width="600" height="500"/>


- 문제 해결 방법
  - 하나의 스레드가 작업이 완료된 이후 다른 스레드가 데이터에 접근하도록 만든다. 즉, 데이터에 한개의 스레드만 접근이 가능하도록 한다

## Synchronized 활용하기
Java에서는 Synchronized를 활용하면 손쉽게 한개의 스레드만 접근 가능하도록 만들 수 있다.
`synchronized`를 메서드 선언부에 붙여주면, 해당 메서드는 한 개의 스레드만 접근 가능하다.
```java
@Transactional
public synchronized void decrease(Long id, Long quantity)  {
    Stock stock = stockRepository.findById(id).orElseThrow();
    stock.decrease(quantity);

    stockRepository.saveAndFlush(stock);
}
```
하지만 위 코드로 테스트하면 여전히 실패하는데, 그 이유는 `@Transactional` 동작 방식 때문이다.   
Spring에서는 `@Transactional` 를 사용하면 아래 예시 코드와 같이 매핑한 클래스를 새로 만들어서 생성하는 구조이다
```java
# proxy example
   public void decrease(Long id, Long quantity)  {
        startTransaction(); // 1. 트랜잭션 시작
        
        stockService.decrease(id, quantity); // 2. decrease 메서드 호출

        endTransaction(); // 3. 트랜잭션 종료
    }
```
트랜잭션 종료 시점에 데이터베이스를 업데이트한다. 따라서 실제 데이터베이스가 업데이트 되기 전에(`endTransaction()`) 다른 스레드가 `decrease()` 메서드 호출 가능하다.
그럼 결국 다른 스레드는 갱신되기 이전 값을 가져가서 이전과 동일한 문제가 발생한다.

→ 일단 `@Transactional` 을 제거하면 문제는 해결 가능 ([예시 코드](https://github.com/SuyeonChoi/stock-system/commit/5edc45cd06f0e3ccd531748254d14dd38f47d99e))


### synchronized를 이용했을 때 발생할 수 있는 문제

- 자바의 synchronized는 하나의 프로세스 안에서만 보장된다
- 서버가 1대인 경우, DB 접근을 서버 하나에서만 하면 괜찮으나, 2개 이상인 경우 여러 서버가 DB에 접근하게 된다.
- 실제 운영중인 서비스는 대부분 2대 이상의 서버를 사용하므로 synchronized는 거의 사용하지 않음
- 예시
  ```markdown
  - 10:00 서버1에서 재고 감소 로직 시작
  - 10:01 서버2에서 갱신되지 않은 값으로 재고 감소 로직을 시작
  - 10:05 서버1 재고 감소 로직 종료
  - 10:06 서버2에서 재고 감소 로직 종료
  ```
  - synchronized는 각 프로세스 안에서만 보장되므로, 결국 여러 스레드에서 동시에 데이터에 접근해서 race condition 발생

## Database 활용하기
### 1. Pessimistic Lock
> https://dev.mysql.com/doc/refman/8.0/en/

- 실제 데이터에 Lock을 걸어서 정합성을 맞추는 방법
- exclusive lock 을 걸게되며 다른 트랜잭션에서는 lock이 해제되기 전에 데이터를 가져갈 수 없다
- 데드락이 걸릴 수 있기 때문에 주의해서 사용 필요
- [예시 코드](https://github.com/SuyeonChoi/stock-system/commit/9ff1fc49fdcedb7ab2ded8ae1810b0f50c973e04)
  ```java
  public interface StockRepository extends JpaRepository<Stock, Long> {
  
      @Lock(LockModeType.PESSIMISTIC_WRITE) // Pessimistic Lock
      @Query("select s from Stock s where s.id = :id")
      Stock findByIdWithPessimisticLock(Long id);
  }
  
  @Service
  public class PessimisticLockStockService {
  
      @Transactional
      public void decrease(Long id, Long quantity) {
          Stock stock = stockRepository.findByIdWithPessimisticLock(id);
  
          stock.decrease(quantity);
  
          stockRepository.save(stock);
      }
  }
  ```
- 쿼리
  ```sql
  # for update 부분에서 락을 걸고, 데이터를 가져온다
  select s1_0.id,s1_0.product_id,s1_0.quantity from stock s1_0 where s1_0.id=? for update;
  ```
- 장점
    - 충돌이 빈번하게 일어나는 경우, Optimistic Lock 보다 성능이 좋다
    - Lock을 통해 업데이트를 제어하므로 데이터 정합성 보장
- 단점
    - 별도의 Lock을 잡으므로, 성능 감소 가능성 주의

### 2. Optimistic Lock
> https://dev.mysql.com/doc/refman/8.0/en/locking-functions.html

- 실제로 Lock 을 이용하지 않고 버전을 이용해 정합성을 맞추는 방법
  - 비관적 락은과 락 획득 시점이 근본적으로 다르다. 비관적 락은 데이터 사용 전 선제적으로 락을 획득하고, 낙관적 락은 일단 사용한 후 버전 등을 확인하여 충돌 시 처리한다는 점
- 먼저 데이터를 읽은 후, update를 수행하는 시점에 현재 내가 읽은 버전이 맞는지 확인하여 업데이트
- 내가 읽은 버전에서 수정 사항이 생긴 경우에는 application에서 다시 읽은 후 작업 수행
- [예시 코드](https://github.com/SuyeonChoi/stock-system/commits/main/)
  ```java
  public interface StockRepository extends JpaRepository<Stock, Long> {
      @Lock(LockModeType.OPTIMISTIC)
      @Query("select s from Stock s where s.id = :id")
      Stock findByIdWithOptimisticLock(Long id);
  }

  @Service
  public class OptimisticLockStockService {
      // ...  
      @Transactional
      public void decrease(Long id, Long quantity) {
          Stock stock = stockRepository.findByIdWithOptimisticLock(id);
          stock.decrease(quantity);
          stockRepository.save(stock);
      }
  }

  @Component
  public class OptimisticLockStockFacade {
      // ...  
      public void decrease(Long id, Long quantity) throws InterruptedException {
          while (true) { // 업데이트 실패한 경우 재시도
              try {
                  optimisticLockStockService.decrease(id, quantity);
                  break;
              } catch (Exception e) {
                  Thread.sleep(50);
              }
          }
      }
  }
  ```
- 쿼리
  ```sql
  select s1_0.id,s1_0.product_id,s1_0.quantity,s1_0.version from stock s1_0 where s1_0.id=?;
  update stock set product_id=?,quantity=?,version=? where id=? and version=?;
  ```
- 장점
  - 별도의 락을 잡지 않으므로 Pessimistic Lock 보단 성능상 이점
- 단점
  - 업데이트가 실패한 경우의 재시도 로직을 개발자가 직접 작성해야한다는 번거로움


### 3. Named Lock
> https://dev.mysql.com/doc/refman/8.0/en/metadata-locking.html

- 이름을 가진 metadata locking
- 이름을 가진 lock 을 획득한 후 해제할 때까지 다른 세션은 이 lock 을 획득할 수 없도록 한다
    - 데이터 행/테이블이 아닌 개발자가 지정한 '이름'을 잠그는 메커니즘
    - 예를 들어 세션 1이 ‘1’이라는 이름으로 락을 거는 경우, 다른 세션에서는 세션1이 락을 해제한 후에 락을 획득 가능
- **(주의)** 트랜잭션이 종료될 때 lock 이 자동으로 해제되지 않기 때문에 별도의 명령어로 해제를 수행하거나 선점 시간이 끝나야 해제된다
  - MySQL에서는 get_lock() 명령어로 락 획득, release_lock() 명령어로 락 해제 
- Pessimistic Lock과 비슷해 보이나, Pessimistic Lock은 row나 테이블 단위로 락을 걸고, Named Lock은 row나 테이블 단위가 아닌, 메타 데이터에 락을 거는 방식이다.
- 예제 코드
  ```java
  # 예제 코드에서는 편의성을 위해 JPA Native Query 기능을 활용하고, 동일한 데이터 소스를 사용하나, 실무에서 사용할 때는 데이터 소스 분리 추천
  # 같은 데이터 소스를 사용하면 커넥션 풀이 부족해지는 현상으로 인해, 다른 서비스에도 영향을 끼칠 수 있음. 
  public interface LockRepository extends JpaRepository<Stock, Long> {
      @Query(value = "select get_lock(:key, 3000)", nativeQuery = true)
      void getLock(String key);
  
      @Query(value = "select release_lock(:key)", nativeQuery = true)
      void releaseLock(String key);
  }

  @Component
  public class NamedLockStockFacade {
      //...  
      @Transactional
      public void decrease(Long id, Long quantity) {
          try {
              lockRepository.getLock(id.toString());
              stockService.decrease(id, quantity);
          } finally {
              lockRepository.releaseLock(id.toString());
          }
      }
  }

  @Service
  public class StockService {
      @Transactional(propagation = Propagation.REQUIRES_NEW) // 부모의 트랜잭션과 별도로 실행되어야함
      public void decrease(Long id, Long quantity) {
          Stock stock = stockRepository.findById(id).orElseThrow();
          stock.decrease(quantity);
          stockRepository.saveAndFlush(stock);
      }
  }
  ```
- 장점
    - Pessimistic Lock은 타임아웃을 구현하기 어려우나, NamedLock은 타임아웃 쉽게 구현 가능
    - NamedLock은 주로 분산락을 구현할 때 사용
        - 특정 '이름'을 잠가 여러 서버나 프로세스 간의 자원 접근을 동기화하거나, 특정 작업의 중복 실행을 방지하는 분산 락 구현에 유용
        - 데이터 삽입 시에 정합성을 맞춰야하는 경우에도 사용 가능
- 단점
    - 트랜잭션 종료 시에 락 해제 필요. 세션 관리를 주의해서 사용해야함
    - 구현 방법이 복잡할 수 있음

### 정리
- 충돌이 빈번하게 일어나는 경우 Pessimistic Lock 추천, 그렇지 않은 경우 Optimistic Lock

## Redis 활용하기
분산락을 구현할 때 사용하는 대표적인 라이브러리 2가지
1. Lettuce
2. Redisson  

두 라이브러리는 분산 시스템에서 여러 스레드/프로세스가 공유 자원에 안전하게 접근하도록 돕는 분산 락 기능을 제공한다

### Lettuce  
- setnx 명령어를 활용하여 분산락 구현
    - `set if not exist`의 줄임말. key와 value를 set할 때, 기존의 값이 없는 경우에만 set
- spin lock 방식으로 개발자가 retry 로직을 작성 필요
    - spin lock이란 락을 획득하려는 스레드가 락을 사용할 수 있는지 반복적으로 확인하면서 락 획득을 시도하는 방식
- MySQL Named Lock과 거의 유사하나, redis를 사용한다는 점 + session 관리에 신경쓰지 않아도 된다는 점이 다르다
- [예시 코드](https://github.com/SuyeonChoi/stock-system/commit/45af3f8dbec390202ff055e1bb2d06181d80c361)
  ```java
    @Component
    public class RedisLockRepository {
        private RedisTemplate<String, String> redisTemplate;
        //..
        public Boolean lock(Long key) {
            return redisTemplate
                    .opsForValue()
                    .setIfAbsent(generateKey(key), "lock", Duration.ofMillis(3_000));
        }
    
        public Boolean unlock(Long key) {
            return redisTemplate.delete(generateKey(key));
        }
    
        private String generateKey(Long key) {
            return key.toString();
        }
    }
    
    @Component
    public class LettuceLockStockFacade {
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
  ```
- 장점
  - 구현이 간단함
- 단점
  - 스핀락 방식이므로 레디스에 부하를 줄 수 있음. 그래서 락 획득 재시도 간에 Thread.sleep()

### Redisson
- pub-sub 기반으로 Lock 구현 제공
    - 레디스는 자신이 점유하고 있는 락을 해제할 때 채널에 메세지를 보내줌으로써 락을 획득해야하는 스레드들에게 락 획득을 하라고 전달해줌
    - 안내를 받은 스레드는 메세지를 받았을 때 락 획득 시도
- Lettuce 달리 별도의 retry 로직을 작성하지 않아도 됨
- Lettuce는 계속 락 획득을 시도하는 반면 레디스는 락 해제가 되었을 때 한번 혹은 몇번만 시도를 하므로 redis에 부하를 줄여줌
- Redisson 경우에는 락 관련된 클래스들을 라이브러리에서 제공해줘서 별도의 레포지토리를 작성하지 않아도 됨. 로직 실행 전후로 락 획득 해제는 필요
- 예제 코드
  ```java
    @Component
    public class RedissonLockStockFacade {
        private RedissonClient redissonClient;
        private StockService stockService;
        
        public void decrease(Long id, Long quantity) {
            RLock lock = redissonClient.getLock(id.toString()); // // 락 객체 가져오기
        
            try {
                boolean available = lock.tryLock(10, 1, TimeUnit.SECONDS);// 몇 초동안 락 획득을 시도할 것인지, 점유할 것인지 설정
                if (!available) { // 락 획득에 실패하면 로그 남기고 리턴
                    System.out.println("lock 획득 실패");
                    return;
                }
                stockService.decrease(id, quantity); // 락을 획득한 경우 재고 감소
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }
    }
  ```
- 장점
  - PubSub 기반의 구현이기 때문에 레디스 부하를 줄여준다
- 단점
  - 구현이 다소 복잡
  - 별도의 라이브러리를 사용해야한다는 부담
 
### Luttuce vs. Redisson
- Lettuce
  - 간단한 구현
  - spring data redis 를 이용하면 lettuce 가 Spring Data Redis의 기본 라이브러리이므로 별도의 라이브러리를 사용하지 않아도 된다.
  - spin lock 방식이기때문에 동시에 많은 스레드가 lock 획득 대기 상태라면 redis 에 부하가 갈 수 있다.
- Redisson
  - 락 획득 재시도를 기본으로 제공한다.
  - pub-sub 방식으로 구현이 되어있기 때문에 lettuce 와 비교했을 때 redis 에 부하가 덜 간다.
  - 별도의 라이브러리를 사용해야한다.
  - lock 을 라이브러리 차원에서 제공해주기 떄문에 라이브러리 사용법을 공부해야 한다.
- 실무에서는?
    - 재시도가 필요하지 않은 lock 은 lettuce 활용
    - 재시도가 필요한 경우에는 redisson 를 활용

## MySQL vs. Redis 비교 정리
- MySQL
    - 이미 MySQL 을 사용하고 있다면 별도의 비용없이 사용 가능
    - 어느 정도의 트래픽까지는 문제없이 활용이 가능하다.
    - Redis 보다는 성능이 좋지 않다.
- Redis
    - 활용중인 Redis 가 없다면 별도의 구축비용과 인프라 관리비용이 발생
    - MySQL 보다 성능이 좋다. 
- 실무에서는?
    - 비용적 여유가 없거나 MySQL로 처리가 가능할 정도의 트래픽이라면 MySQL 활용
    - 비용적 여유가 있거나 MySQL로 처리가 불가능할 정도의 트래픽이라면 Redis 도입





















