package com.github.ambry.rest;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.restservice.BlobStorageService;
import com.github.ambry.restservice.MockBlobStorageService;
import com.github.ambry.restservice.NioServer;
import com.github.ambry.restservice.RestMethod;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/**
 * Tests NettyServer end to end (primarily to indirectly test NettyMessageProcessor and NettyResponseHandler).
 */
public class NettyEndToEndTest {
  private static String NETTY_SERVER_PORT = "8088";
  private static String NETTY_SERVER_ALTERNATE_PORT = "8089";

  private static RestRequestDelegator restRequestDelegator;
  private static NioServer nioServer;

  private static int POLL_MAGIC_TIMEOUT = 30; //seconds

  /**
   * Preps up for the tests by creating and starting NettyServer. Called just once before all the tests.
   * @throws InstantiationException
   * @throws IOException
   */
  @BeforeClass
  public static void startNettyServer()
      throws InstantiationException, IOException {
    restRequestDelegator = createRestRequestDelegator(null);
    nioServer = createNettyServer(restRequestDelegator, null);
    restRequestDelegator.start();
    nioServer.start();
  }

  /**
   * Shuts down the NettyServer and RequestDelegator. Called just once after all the tests.
   */
  @AfterClass
  public static void shutdownNettyServer() {
    nioServer.shutdown();
    restRequestDelegator.shutdown();
  }

  /**
   * Test the handling of messages given good input.
   * @throws Exception
   */
  @Test
  public void handleMessageSuccessTest()
      throws Exception {
    FullHttpRequest request;

    /**
     * MockBlobStorageService just echoes back the HttpMethod equivalent RestMethod.
     * This is just a test to see that different methods are acknowledged correctly and not for actual functionality -
     * that goes into an integration test.
     */
    request = createRequest(HttpMethod.GET, "/");
    doHandleMessageSuccessTest(request, RestMethod.GET.toString(), NETTY_SERVER_PORT);

    request = createRequest(HttpMethod.POST, "/");
    doHandleMessageSuccessTest(request, RestMethod.POST.toString(), NETTY_SERVER_PORT);

    request = createRequest(HttpMethod.DELETE, "/");
    doHandleMessageSuccessTest(request, RestMethod.DELETE.toString(), NETTY_SERVER_PORT);

    request = createRequest(HttpMethod.HEAD, "/");
    doHandleMessageSuccessTest(request, RestMethod.HEAD.toString(), NETTY_SERVER_PORT);
  }

  /**
   * Test the NettyMessageProcessor given bad input
   * @throws Exception
   */
  @Test
  public void badInputStreamTest()
      throws Exception {
    // duplicate request test
    doDuplicateRequestTest(NETTY_SERVER_PORT);
  }

  /**
   * Exercises some internals (mostly error handling) of NettyMessageProcessor and NettyResponseHandler by wilfully
   * introducing exceptions through MockBlobStorageService.
   * @throws Exception
   */
  @Test
  public void handleMessageFailureTest()
      throws Exception {
    FullHttpRequest request;

    // rest exception
    request = createRequestForExceptionDuringHandle(MockBlobStorageService.OPERATION_THROW_HANDLING_REST_EXCEPTION);
    doHandleMessageFailureTest(request, HttpResponseStatus.INTERNAL_SERVER_ERROR, NETTY_SERVER_PORT);

    // runtime exception
    request = createRequestForExceptionDuringHandle(MockBlobStorageService.OPERATION_THROW_HANDLING_RUNTIME_EXCEPTION);
    doHandleMessageFailureTest(request, HttpResponseStatus.INTERNAL_SERVER_ERROR, NETTY_SERVER_PORT);

    // unknown http method
    request = createRequest(HttpMethod.TRACE, "/");
    doHandleMessageFailureTest(request, HttpResponseStatus.BAD_REQUEST, NETTY_SERVER_PORT);

    // do success test at the end to make sure that server is alive
    request = createRequest(HttpMethod.GET, "/");
    doHandleMessageSuccessTest(request, RestMethod.GET.toString(), NETTY_SERVER_PORT);
  }

  /**
   * Tests a scenario where the request delegator fails
   * @throws Exception
   */
  @Test
  public void requestDelegatorFailureTest()
      throws Exception {
    // start a new netty server (on a different port) with a new request delegator
    Properties properties = new Properties();
    properties.setProperty(NettyConfig.PORT_KEY, NETTY_SERVER_ALTERNATE_PORT);

    RestRequestDelegator delegator = createRestRequestDelegator(properties);
    NioServer server = createNettyServer(delegator, properties);
    server.start();

    // we haven't started the request delegator, so any request we send should result in a failure
    FullHttpRequest request = createRequest(HttpMethod.GET, "/");
    doHandleMessageFailureTest(request, HttpResponseStatus.INTERNAL_SERVER_ERROR, NETTY_SERVER_ALTERNATE_PORT);

    // now start the delegator and make sure everything is ok
    delegator.start();
    doHandleMessageSuccessTest(request, RestMethod.GET.toString(), NETTY_SERVER_ALTERNATE_PORT);
    server.shutdown();
    delegator.shutdown();
  }

  // helpers
  // general
  private FullHttpRequest createRequest(HttpMethod httpMethod, String uri)
      throws JSONException {
    return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, uri);
  }

  /**
   * Converts the content in HttpContent to a human readable string.
   * @param httpContent
   * @return
   * @throws IOException
   */
  private String getContentString(HttpContent httpContent)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    httpContent.content().readBytes(out, httpContent.content().readableBytes());
    return out.toString("UTF-8");
  }

  // Before/After class helpers
  private static NettyServer createNettyServer(RestRequestDelegator restRequestDelegator, Properties properties)
      throws InstantiationException {
    if (properties == null) {
      // dud properties. should pick up defaults
      properties = new Properties();
    }
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    return new NettyServer(verifiableProperties, new MetricRegistry(), restRequestDelegator);
  }

  private static RestRequestDelegator createRestRequestDelegator(Properties properties)
      throws InstantiationException, IOException {
    RestServerMetrics restServerMetrics = new RestServerMetrics(new MetricRegistry());
    BlobStorageService blobStorageService = getBlobStorageService(properties);
    return new RestRequestDelegator(1, restServerMetrics, blobStorageService);
  }

  private static BlobStorageService getBlobStorageService(Properties properties)
      throws IOException {
    if (properties == null) {
      // dud properties. should pick up defaults
      properties = new Properties();
    }
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    return new MockBlobStorageService(verifiableProperties, new MockClusterMap(), new MetricRegistry());
  }

  // handleMessageSuccessTest() helpers

  /**
   * Sends a request and expects a certain response. If response doesn't match, declares test failure.
   * @param httpRequest
   * @param expectedResponse
   * @param serverPort
   * @throws Exception
   */
  public void doHandleMessageSuccessTest(FullHttpRequest httpRequest, String expectedResponse, String serverPort)
      throws Exception {
    LinkedBlockingQueue<HttpObject> contentQueue = new LinkedBlockingQueue<HttpObject>();
    LinkedBlockingQueue<HttpObject> responseQueue = new LinkedBlockingQueue<HttpObject>();
    NettyClient nettyClient = new NettyClient(Integer.parseInt(serverPort), contentQueue, responseQueue);
    contentQueue.offer(httpRequest);

    nettyClient.start(); // request gets sent.
    try {
      boolean responseReceived = false;
      while (true) {
        HttpObject httpObject = responseQueue.poll(POLL_MAGIC_TIMEOUT, TimeUnit.SECONDS); // wait for a response
        if (httpObject != null) {
          if (httpObject instanceof HttpResponse) {
            responseReceived = true;
            HttpResponse response = (HttpResponse) httpObject;
            assertTrue("Received a bad response", response.getDecoderResult().isSuccess());
            assertEquals("Response status is not OK", HttpResponseStatus.OK, response.getStatus());
            assertEquals("Unexpected content type", "text/plain",
                response.headers().get(HttpHeaders.Names.CONTENT_TYPE));
          } else if (httpObject instanceof HttpContent) {
            if (httpObject instanceof LastHttpContent) {
              break;
            }
            assertTrue("Received HttpContent without receiving a response first", responseReceived);
            String content = getContentString((HttpContent) httpObject);
            assertEquals("Did not get expected reply from server", expectedResponse, content);
          } else {
            fail("Unknown HttpObject - " + httpObject.getClass());
          }
          ReferenceCountUtil.release(httpObject); // make sure we decrease refCnt
        } else {
          fail("Did not receive any content in 30 seconds. There is an error or the timeout needs to increase");
        }
      }
    } finally {
      nettyClient.shutdown();
    }
  }

  // badInputStreamTest() helpers

  /**
   * Sends two requests in one stream and expects an error to be returned.
   * @param serverPort
   * @throws Exception
   */
  private void doDuplicateRequestTest(String serverPort)
      throws Exception {
    LinkedBlockingQueue<HttpObject> contentQueue = new LinkedBlockingQueue<HttpObject>();
    LinkedBlockingQueue<HttpObject> responseQueue = new LinkedBlockingQueue<HttpObject>();
    NettyClient nettyClient = new NettyClient(Integer.parseInt(serverPort), contentQueue, responseQueue);
    contentQueue.offer(createRequest(HttpMethod.GET, "/"));
    contentQueue.offer(createRequest(HttpMethod.GET, "/")); // this will trigger an error in NettyMessageProcessor.

    nettyClient.start();
    try {
      HttpObject httpObject = responseQueue.poll(POLL_MAGIC_TIMEOUT, TimeUnit.SECONDS);
      if (httpObject != null && httpObject instanceof HttpResponse) {
        HttpResponse response = (HttpResponse) httpObject;
        assertTrue("Received a bad response", response.getDecoderResult().isSuccess());
        assertEquals("Response status is not Bad Request", HttpResponseStatus.BAD_REQUEST, response.getStatus());
      } else {
        fail("Did not receive a response");
      }
    } finally {
      nettyClient.shutdown();
    }
  }

  // handleMessageFailureTest() helpers

  /**
   * Sends a request that is expected to trigger an error and expects the error in the response. If no error or
   * different from the one being expected, it fails the test.
   * @param httpRequest
   * @param expectedStatus
   * @param serverPort
   * @throws Exception
   */
  private void doHandleMessageFailureTest(FullHttpRequest httpRequest, HttpResponseStatus expectedStatus,
      String serverPort)
      throws Exception {
    LinkedBlockingQueue<HttpObject> contentQueue = new LinkedBlockingQueue<HttpObject>();
    LinkedBlockingQueue<HttpObject> responseQueue = new LinkedBlockingQueue<HttpObject>();
    NettyClient nettyClient = new NettyClient(Integer.parseInt(serverPort), contentQueue, responseQueue);
    contentQueue.offer(httpRequest);

    nettyClient.start();
    try {
      HttpObject httpObject = responseQueue.poll(POLL_MAGIC_TIMEOUT, TimeUnit.SECONDS);
      if (httpObject != null && httpObject instanceof HttpResponse) {
        HttpResponse response = (HttpResponse) httpObject;
        assertTrue("Received a bad response", response.getDecoderResult().isSuccess());
        assertEquals("Response status differs from expectation", expectedStatus, response.getStatus());
      } else {
        fail("Did not receive a response");
      }
    } finally {
      nettyClient.shutdown();
    }
  }

  private FullHttpRequest createRequestForExceptionDuringHandle(String exceptionOperationType)
      throws Exception {
    JSONObject executionData = new JSONObject();
    executionData.put(MockBlobStorageService.OPERATION_TYPE_KEY, exceptionOperationType);
    executionData.put(MockBlobStorageService.OPERATION_DATA_KEY, new JSONObject());

    FullHttpRequest request = createRequest(HttpMethod.GET, "/");
    request.headers().add(MockBlobStorageService.EXECUTION_DATA_HEADER_KEY, executionData);
    return request;
  }
}
