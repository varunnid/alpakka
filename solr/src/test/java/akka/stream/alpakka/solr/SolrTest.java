/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.solr;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.alpakka.solr.javadsl.SolrFlow;
import akka.stream.alpakka.solr.javadsl.SolrSink;
import akka.stream.alpakka.solr.javadsl.SolrSource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.beans.Field;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.stream.CloudSolrStream;
import org.apache.solr.client.solrj.io.stream.StreamContext;
import org.apache.solr.client.solrj.io.stream.TupleStream;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParser;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.solr.common.SolrInputDocument;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SolrTest {
  private static MiniSolrCloudCluster cluster;
  private static ActorSystem system;
  private static ActorMaterializer materializer;
  private static String zkHost;
  private static ZkTestServer zkTestServer;

  // #define-class
  public static class Book {
    public String title;

    public String comment;

    public String router;

    public Book() {}

    public Book(String title) {
      this.title = title;
    }

    public Book(String title, String comment) {
      this.title = title;
      this.comment = comment;
    }

    public Book(String title, String comment, String router) {
      this.title = title;
      this.comment = comment;
      this.router = router;
    }
  }

  Function<Book, SolrInputDocument> bookToDoc =
      book -> {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("title", book.title);
        doc.setField("comment", book.comment);
        if (book.router != null) doc.setField("router", book.router);
        return doc;
      };

  Function<Tuple, Book> tupleToBook =
      tuple -> {
        String title = tuple.getString("title");
        return new Book(title, tuple.getString("comment"));
      };
  // #define-class

  @Test
  public void solrInputDocumentStream() throws Exception {
    // Copy collection1 to collection2 through document stream
    createCollection("collection2"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-document
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  Book book = tupleToBook.apply(tuple);
                  SolrInputDocument doc = bookToDoc.apply(book);
                  return IncomingUpsertMessage.create(doc);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection2", settings, cluster.getSolrClient()), materializer);
    // #run-document

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection2");

    CompletionStage<List<String>> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    assertEquals(expect, result);
  }

  @Test
  public void beanStream() throws Exception {
    // Copy collection1 to collection3 through bean stream
    createCollection("collection3"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #define-bean
    class BookBean {
      @Field("title")
      public String title;

      public BookBean(String title) {
        this.title = title;
      }
    }
    // #define-bean

    // #run-bean
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  String title = tuple.getString("title");
                  return IncomingUpsertMessage.create(new BookBean(title));
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.beans("collection3", settings, cluster.getSolrClient(), BookBean.class),
                materializer);
    // #run-bean

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection3");

    CompletionStage<List<String>> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    assertEquals(expect, result);
  }

  @Test
  public void typedStream() throws Exception {
    // Copy collection1 to collection4 through typed stream
    createCollection("collection4"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-typed
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(tuple -> IncomingUpsertMessage.create(tupleToBook.apply(tuple)))
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.typeds(
                    "collection4", settings, bookToDoc, cluster.getSolrClient(), Book.class),
                materializer);
    // #run-typed

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection4");

    CompletionStage<List<String>> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    assertEquals(expect, result);
  }

  @Test
  public void flow() throws Exception {
    // Copy collection1 to collection5 through typed stream
    createCollection("collection5"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-flow
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(tuple -> IncomingUpsertMessage.create(tupleToBook.apply(tuple)))
            .groupedWithin(5, Duration.ofMillis(10))
            .via(
                SolrFlow.typeds(
                    "collection5", settings, bookToDoc, cluster.getSolrClient(), Book.class))
            .runWith(Sink.ignore(), materializer);
    // #run-flow

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection5");

    CompletionStage<List<String>> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    assertEquals(expect, result);
  }

  @Test
  public void testKafkaExample() throws Exception {
    createCollection("collection6"); // create new collection

    // #kafka-example
    // We're going to pretend we got messages from kafka.
    // After we've written them to Solr, we want
    // to commit the offset to Kafka

    List<KafkaMessage> messagesFromKafka =
        Arrays.asList(
            new KafkaMessage(new Book("Book 1"), new KafkaOffset(0)),
            new KafkaMessage(new Book("Book 2"), new KafkaOffset(1)),
            new KafkaMessage(new Book("Book 3"), new KafkaOffset(2)));

    final KafkaCommitter kafkaCommitter = new KafkaCommitter();

    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);

    Source.from(messagesFromKafka) // Assume we get this from Kafka
        .map(
            kafkaMessage -> {
              Book book = kafkaMessage.book;
              // Transform message so that we can write to elastic
              return IncomingUpsertMessage.create(book, kafkaMessage.offset);
            })
        .groupedWithin(5, Duration.ofMillis(10))
        .via(
            SolrFlow.typedsWithPassThrough(
                "collection6", settings, bookToDoc, cluster.getSolrClient(), Book.class))
        .map(
            messageResults -> {
              messageResults
                  .stream()
                  .forEach(
                      result -> {
                        if (result.status() != 0) {
                          throw new RuntimeException("Failed to write message to elastic");
                        }
                        // Commit to kafka
                        kafkaCommitter.commit(result.passThrough());
                      });
              return NotUsed.getInstance();
            })
        .runWith(Sink.seq(), materializer) // Run it
        .toCompletableFuture()
        .get(); // Wait for it to complete
    // #kafka-example

    // Make sure all messages was committed to kafka
    assertEquals(Arrays.asList(0, 1, 2), kafkaCommitter.committedOffsets);

    TupleStream stream = getTupleStream("collection6");

    CompletionStage<List<String>> res2 =
        SolrSource.fromTupleStream(stream)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res2.toCompletableFuture().get());

    assertEquals(
        messagesFromKafka.stream().map(m -> m.book.title).sorted().collect(Collectors.toList()),
        result.stream().sorted().collect(Collectors.toList()));
  }

  @Test
  public void deleteDocuments() throws Exception {
    // Copy collection1 to collection2 through document stream
    createCollection("collection7"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-document
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  Book book = tupleToBook.apply(tuple);
                  SolrInputDocument doc = bookToDoc.apply(book);
                  return IncomingUpsertMessage.create(doc);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection7", settings, cluster.getSolrClient()), materializer);
    // #run-document

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection7");

    // #delete-documents
    CompletionStage<Done> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(
                t ->
                    IncomingDeleteMessageByIds.<SolrInputDocument>create(
                        tupleToBook.apply(t).title))
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection7", settings, cluster.getSolrClient()), materializer);
    // #delete-documents

    res2.toCompletableFuture().get();

    TupleStream stream3 = getTupleStream("collection7");

    CompletionStage<List<String>> res3 =
        SolrSource.fromTupleStream(stream3)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res3.toCompletableFuture().get());

    List<String> expect = Arrays.asList();

    assertEquals(expect, result);
  }

  @Test
  public void atomicUpdateDocuments() throws Exception {
    // Copy collection1 to collection2 through document stream
    createCollection("collection8"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-document
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  Book book = new Book(tupleToBook.apply(tuple).title, "Written by good authors.");
                  SolrInputDocument doc = bookToDoc.apply(book);
                  return IncomingUpsertMessage.create(doc);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection8", settings, cluster.getSolrClient()), materializer);
    // #run-document

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection8");

    // #update-atomically-documents
    CompletionStage<Done> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(
                t -> {
                  Map<String, Map<String, Object>> m1 = new HashMap<>();
                  Map<String, Object> m2 = new HashMap<>();
                  m2.put("set", (t.fields.get("comment") + " It's is a good book!!!"));
                  m1.put("comment", m2);
                  return IncomingAtomicUpdateMessage.<SolrInputDocument>create(
                      "title", t.fields.get("title").toString(), m1);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection8", settings, cluster.getSolrClient()), materializer);
    // #update-atomically-documents

    res2.toCompletableFuture().get();

    cluster.getSolrClient().commit("collection8");

    TupleStream stream3 = getTupleStream("collection8");

    CompletionStage<List<String>> res3 =
        SolrSource.fromTupleStream(stream3)
            .map(
                t -> {
                  Book b = tupleToBook.apply(t);
                  return b.title + ". " + b.comment;
                })
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res3.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency. Written by good authors. It's is a good book!!!",
            "Akka in Action. Written by good authors. It's is a good book!!!",
            "Effective Akka. Written by good authors. It's is a good book!!!",
            "Learning Scala. Written by good authors. It's is a good book!!!",
            "Programming in Scala. Written by good authors. It's is a good book!!!",
            "Scala Puzzlers. Written by good authors. It's is a good book!!!",
            "Scala for Spark in Production. Written by good authors. It's is a good book!!!");

    assertEquals(expect, result);
  }

  @Test
  public void atomicUpdateDocumentsWithRouter() throws Exception {
    // Copy collection1 to collection2 through document stream
    createCollection("collection8-1", "router"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  Book book =
                      new Book(
                          tupleToBook.apply(tuple).title,
                          "Written by good authors.",
                          "router-value");
                  SolrInputDocument doc = bookToDoc.apply(book);
                  return IncomingUpsertMessage.create(doc);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection8-1", settings, cluster.getSolrClient()),
                materializer);

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection8-1");

    CompletionStage<Done> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(
                t -> {
                  Map<String, Map<String, Object>> m1 = new HashMap<>();
                  Map<String, Object> m2 = new HashMap<>();
                  m2.put("set", (t.fields.get("comment") + " It's is a good book!!!"));
                  m1.put("comment", m2);
                  return IncomingAtomicUpdateMessage.<SolrInputDocument>create(
                      "title", t.fields.get("title").toString(), "router-value", m1);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection8-1", settings, cluster.getSolrClient()),
                materializer);

    res2.toCompletableFuture().get();

    cluster.getSolrClient().commit("collection8-1");

    TupleStream stream3 = getTupleStream("collection8-1");

    CompletionStage<List<String>> res3 =
        SolrSource.fromTupleStream(stream3)
            .map(
                t -> {
                  Book b = tupleToBook.apply(t);
                  return b.title + ". " + b.comment;
                })
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res3.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency. Written by good authors. It's is a good book!!!",
            "Akka in Action. Written by good authors. It's is a good book!!!",
            "Effective Akka. Written by good authors. It's is a good book!!!",
            "Learning Scala. Written by good authors. It's is a good book!!!",
            "Programming in Scala. Written by good authors. It's is a good book!!!",
            "Scala Puzzlers. Written by good authors. It's is a good book!!!",
            "Scala for Spark in Production. Written by good authors. It's is a good book!!!");

    assertEquals(expect, result);
  }

  @Test
  public void deleteDocumentsByQuery() throws Exception {
    // Copy collection1 to collection2 through document stream
    createCollection("collection9"); // create a new collection
    TupleStream stream = getTupleStream("collection1");

    // #run-document
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(5);
    CompletionStage<Done> f1 =
        SolrSource.fromTupleStream(stream)
            .map(
                tuple -> {
                  Book book = tupleToBook.apply(tuple);
                  SolrInputDocument doc = bookToDoc.apply(book);
                  return IncomingUpsertMessage.create(doc);
                })
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection9", settings, cluster.getSolrClient()), materializer);
    // #run-document

    f1.toCompletableFuture().get();

    TupleStream stream2 = getTupleStream("collection9");

    // #delete-documents-query
    CompletionStage<Done> res2 =
        SolrSource.fromTupleStream(stream2)
            .map(
                t ->
                    IncomingDeleteMessageByQuery.<SolrInputDocument>create(
                        "title:\"" + t.fields.get("title").toString() + "\""))
            .groupedWithin(5, Duration.ofMillis(10))
            .runWith(
                SolrSink.documents("collection9", settings, cluster.getSolrClient()), materializer);
    // #delete-documents-query

    res2.toCompletableFuture().get();

    TupleStream stream3 = getTupleStream("collection9");

    CompletionStage<List<String>> res3 =
        SolrSource.fromTupleStream(stream3)
            .map(t -> tupleToBook.apply(t).title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(res3.toCompletableFuture().get());

    List<String> expect = Arrays.asList();

    assertEquals(expect, result);
  }

  @BeforeClass
  public static void setup() throws Exception {
    setupCluster();

    // #init-client
    zkHost = "127.0.0.1:9984/solr";
    // #init-client

    // #init-mat
    system = ActorSystem.create();
    materializer = ActorMaterializer.create(system);
    // #init-mat

    new UpdateRequest()
        .add("title", "Akka in Action")
        .add("title", "Programming in Scala")
        .add("title", "Learning Scala")
        .add("title", "Scala for Spark in Production")
        .add("title", "Scala Puzzlers")
        .add("title", "Effective Akka")
        .add("title", "Akka Concurrency")
        .commit(cluster.getSolrClient(), "collection1");
  }

  @AfterClass
  public static void teardown() throws Exception {
    cluster.shutdown();
    zkTestServer.shutdown();
    TestKit.shutdownActorSystem(system);
  }

  static class KafkaCommitter {
    List<Integer> committedOffsets = new ArrayList<>();

    public KafkaCommitter() {}

    void commit(KafkaOffset offset) {
      committedOffsets.add(offset.offset);
    }
  }

  static class KafkaOffset {
    final int offset;

    public KafkaOffset(int offset) {
      this.offset = offset;
    }
  }

  static class KafkaMessage {
    final Book book;
    final KafkaOffset offset;

    public KafkaMessage(Book book, KafkaOffset offset) {
      this.book = book;
      this.offset = offset;
    }
  }

  private static void setupCluster() throws Exception {
    File targetDir = new File("solr/target");
    File testWorkingDir = new File(targetDir, "java-solr-" + System.currentTimeMillis());
    if (!testWorkingDir.isDirectory()) {
      boolean mkdirs = testWorkingDir.mkdirs();
    }

    File confDir = new File("solr/src/test/resources/conf");

    String zkDir = testWorkingDir.toPath().resolve("zookeeper/server/data").toString();
    zkTestServer = new ZkTestServer(zkDir, 9984);
    zkTestServer.run();

    cluster =
        new MiniSolrCloudCluster(
            1,
            testWorkingDir.toPath(),
            MiniSolrCloudCluster.DEFAULT_CLOUD_SOLR_XML,
            JettyConfig.builder().setContext("/solr").build(),
            zkTestServer);
    ((ZkClientClusterStateProvider) cluster.getSolrClient().getClusterStateProvider())
        .uploadConfig(confDir.toPath(), "conf");

    cluster.getSolrClient().setIdField("router");
    createCollection("collection1");

    assertTrue(
        !cluster.getSolrClient().getZkStateReader().getClusterState().getLiveNodes().isEmpty());
  }

  private static void createCollection(String name) throws IOException, SolrServerException {
    CollectionAdminRequest.createCollection(name, "conf", 1, 1).process(cluster.getSolrClient());
  }

  private static void createCollection(String name, String router)
      throws IOException, SolrServerException {
    CollectionAdminRequest.createCollection(name, "conf", 1, 1)
        .setRouterField(router)
        .process(cluster.getSolrClient());
  }

  private TupleStream getTupleStream(String collection) throws IOException {
    // #tuple-stream
    StreamFactory factory = new StreamFactory().withCollectionZkHost(collection, zkHost);
    SolrClientCache solrClientCache = new SolrClientCache();
    StreamContext streamContext = new StreamContext();
    streamContext.setSolrClientCache(solrClientCache);

    String expressionStr =
        String.format("search(%s, q=*:*, fl=\"title,comment\", sort=\"title asc\")", collection);
    StreamExpression expression = StreamExpressionParser.parse(expressionStr);
    TupleStream stream = new CloudSolrStream(expression, factory);
    stream.setStreamContext(streamContext);
    // #tuple-stream
    return stream;
  }

  private void documentation() {
    TupleStream stream = null;
    // #define-source
    Source<Tuple, NotUsed> source = SolrSource.fromTupleStream(stream);
    // #define-source
    // #solr-update-settings
    SolrUpdateSettings settings = SolrUpdateSettings.create().withCommitWithin(-1);
    // #solr-update-settings
  }
}
