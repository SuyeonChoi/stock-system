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
    - Pessimistic Lock은 타임아웃을 구현하기 어려우나, NamedLock은 타임 아웃 쉽게 구현 가능
    - NamedLock은 주로 분산락을 구현할 때 사용
        - 특정 '이름'을 잠가 여러 서버나 프로세스 간의 자원 접근을 동기화하거나, 특정 작업의 중복 실행을 방지하는 분산 락 구현에 유용
        - 데이터 삽입 시에 정합성을 맞춰야하는 경우에도 사용 가능
- 단점
    - 트랜잭션 종료 시에 락 해제, 세션 관리를 주의해서 사용해야함
    - 구현 방법이 복잡할 수 있음

### 정리
- 충돌이 빈번하게 일어나는 경우 Pessimistic Lock 추천, 그렇지 않은 경우 Optimistic Lock

## Redis 활용하기


## MySQL vs. Redis 비교 정리






















