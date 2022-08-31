package de.upb.upcy.update.dockerize;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Superclass to bootstrap rabbitmq collectives */
public abstract class RabbitMQCollective {
  public static final String DEFAULT_RABBITMQ_REPLY_TO = "amq.rabbitmq.reply-to";
  public static final int PREFETCH_COUNT = 1;
  private static final Logger logger = LoggerFactory.getLogger(RabbitMQCollective.class);
  private final String queueName;
  private final String replyQueue;
  private final int queue_length;
  private final String rabbitmqUser;
  private final String rabbitmqPass;
  private final boolean workerNode;
  private final String rabbitmqHost;

  /**
   * This queue is used to reduce back pressure to the workers. It will only allow a fixed amount of
   * messages queued with rabbit and block the enqueueing thread until there is place in the queue
   * again.
   */
  private ArrayBlockingQueue<Object> actor_queue;

  private Channel activeChannel;

  /**
   * Uses environment variables to infer field values
   *
   * @param queueName
   */
  public RabbitMQCollective(String queueName) {
    this(
        queueName,
        getRabbitMQHostFromEnvironment(),
        getRabbitMQUserFromEnvironment(),
        getRabbitMQPassFromEnvironment(),
        getWorkerNodeFromEnvironment(),
        DEFAULT_RABBITMQ_REPLY_TO,
        getActorLimit());
  }

  public RabbitMQCollective(
      String queueName,
      String rabbitmqHost,
      String rabbitmqUser,
      String rabbitmqPass,
      boolean workerNode,
      String replyQueueName,
      int queue_length) {
    this.rabbitmqUser = rabbitmqUser;
    this.rabbitmqPass = rabbitmqPass;
    this.workerNode = workerNode;
    this.rabbitmqHost = rabbitmqHost;
    this.queueName = queueName;
    this.replyQueue = replyQueueName;
    this.queue_length = queue_length;

    logger.info("rabbitmqHost: {}", rabbitmqHost);
    logger.info("rabbitmqUser: {}", rabbitmqUser);
    logger.info("workerNode: {}", workerNode);
    logger.info("ACTOR_LIMIT: {}", queue_length);
  }

  public static String getRabbitMQHostFromEnvironment() {
    String res = System.getenv("RABBITMQ_HOST");
    if (res == null || res.isEmpty()) {
      res = "localhost";
    }
    return res;
  }

  public static String getRabbitMQUserFromEnvironment() {
    String res = System.getenv("RABBITMQ_USER");
    if (res == null || res.isEmpty()) {
      res = "guest";
    }
    return res;
  }

  public static String getRabbitMQPassFromEnvironment() {
    String res = System.getenv("RABBITMQ_PASS");
    if (res == null || res.isEmpty()) {
      res = "guest";
    }
    return res;
  }

  public static boolean getWorkerNodeFromEnvironment() {
    String res = System.getenv("WORKER_NODE");

    boolean workerNode;
    if (res == null || res.isEmpty()) {
      workerNode = true;
    } else {
      workerNode = Boolean.parseBoolean(res);
    }

    return workerNode;
  }

  public static int getActorLimit() {
    String res = System.getenv("ACTOR_LIMIT");
    int actorLimit;
    if (res == null || res.isEmpty()) {
      actorLimit = 20;
    } else {
      actorLimit = Integer.parseInt(res);
    }
    return actorLimit;
  }

  private static boolean reachable() {
    try (Socket ignored = new Socket(getRabbitMQHostFromEnvironment(), 5672)) {
      return true;
    } catch (IOException ignored) {
      return false;
    }
  }

  protected void runWorker(Channel channel) throws IOException {
    DeliverCallback deliverCallback =
        (consumerTag, delivery) -> {
          try {
            doWorkerJob(delivery);
          } catch (Exception e) {
            logger.error("[Worker] job failed...", e);
          } finally {
            channel.basicPublish(
                "", delivery.getProperties().getReplyTo(), null, "Polo".getBytes());

            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            logger.info("[Worker] Send Ack");
          }
        };
    channel.basicConsume(getQueueName(), false, deliverCallback, consumerTag -> {});
  }

  protected abstract void doWorkerJob(Delivery delivery) throws IOException;

  protected void runProducer(Channel channel) throws Exception {
    setupResponseListener(channel);

    AMQP.BasicProperties props =
        new AMQP.BasicProperties.Builder().replyTo(getReplyQueue()).build();

    doProducerJob(props);
  }

  protected abstract void doProducerJob(AMQP.BasicProperties props) throws Exception;

  private void setupResponseListener(Channel channel) throws IOException {
    final BlockingQueue<String> response = new ArrayBlockingQueue<>(1);

    channel.basicConsume(
        getReplyQueue(),
        true,
        new DefaultConsumer(channel) {
          @Override
          public void handleDelivery(
              String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
            logger.info("[Producer] Received Ack");
            response.offer(new String(body, StandardCharsets.UTF_8));
            actor_queue.poll();
            logger.info("[Producer] Removed Element from Queue");
          }
        });
  }

  protected void run() throws Exception {
    logger.info("Check if rabbitmq is up and running");
    boolean rabbitIsAvailable = false;
    while (!rabbitIsAvailable) {
      rabbitIsAvailable = reachable();
      if (!rabbitIsAvailable) {
        logger.info("rabbitmq is not available waiting for {} sec", 30);
        Thread.sleep(1000 * 30);
      }
    }

    logger.info("Execute pre-flight check");
    preFlightCheck();
    activeChannel = createChannel();
    if (!workerNode) {
      logger.info("[Producer] Run Producer");
      actor_queue = new ArrayBlockingQueue<>(queue_length);
      runProducer(activeChannel);
    } else {
      logger.info("[Worker] Run Worker");
      runWorker(activeChannel);
      // usually only consumer define a prefetch counts
    }
  }

  /**
   * Is executed before the worker/producer starts its job. Can be used to set up some required
   * resources.
   */
  protected abstract void preFlightCheck() throws IOException;

  private Channel createChannel() throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbitmqHost);
    factory.setAutomaticRecoveryEnabled(true);
    // attempt recovery every 10 seconds
    factory.setNetworkRecoveryInterval(10000);
    factory.setUsername(rabbitmqUser);
    factory.setPassword(rabbitmqPass);

    // do not call in try block like above, otherwise the channel is closed after the loop
    Connection connection = factory.newConnection();
    connection.addShutdownListener(
        cause -> {
          logger.info("Received Connection Shutdown signal with cause: " + cause.getMessage());
          RabbitMQCollective.this.shutdown();
          final boolean hardError = cause.isHardError();
          int signal = -1;

          System.exit(signal);
        });

    Channel channel = connection.createChannel();

    channel.addShutdownListener(
        cause -> {
          logger.info("Received Channel Shutdown signal with cause: " + cause.getMessage());
          RabbitMQCollective.this.shutdown();
          final boolean hardError = cause.isHardError();
          int signal = -1;

          System.exit(signal);
        });

    channel.queueDeclare(queueName, false, false, false, null);
    channel.basicQos(PREFETCH_COUNT);

    return channel;
  }

  public String getReplyQueue() {
    return replyQueue;
  }

  public String getQueueName() {
    return queueName;
  }

  /**
   * Enqueues the given body to the rabbit queue.
   *
   * @param props Rabbit props acquired in the producer job
   * @param body Body of the message that should be enqueued
   * @throws InterruptedException
   * @throws IOException
   */
  public void enqueue(AMQP.BasicProperties props, byte[] body)
      throws IOException, InterruptedException {
    actor_queue.put(body);
    activeChannel.basicPublish("", getQueueName(), props, body);
  }

  public boolean isWorkerNode() {
    return workerNode;
  }

  protected abstract void shutdown();
}
