package com.github.ambry.network;

import com.github.ambry.config.SSLConfig;
import java.io.File;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


public class SSLFactoryTest {

  @Before
  public void setup()
      throws Exception {
  }

  @After
  public void teardown()
      throws Exception {
  }

  @Test
  public void testSSLFactory()
      throws Exception {
    File trustStoreFile = File.createTempFile("truststore", ".jks");
    SSLConfig sslConfig = TestSSLUtils.createSSLConfig("DC1,DC2,DC3", SSLFactory.Mode.SERVER, trustStoreFile, "server");
    SSLConfig clientSSLConfig =
        TestSSLUtils.createSSLConfig("DC1,DC2,DC3", SSLFactory.Mode.CLIENT, trustStoreFile, "client");

    SSLFactory sslFactory = new SSLFactory(sslConfig);
    SSLContext sslContext = sslFactory.getSSLContext();
    SSLSocketFactory socketFactory = sslContext.getSocketFactory();
    Assert.assertNotNull(socketFactory);
    SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
    Assert.assertNotNull(serverSocketFactory);
    SSLEngine serverSideSSLEngine = sslFactory.createSSLEngine("localhost", 9095, SSLFactory.Mode.SERVER);
    TestSSLUtils.verifySSLConfig(sslContext, serverSideSSLEngine, false);

    //client
    sslFactory = new SSLFactory(clientSSLConfig);
    sslContext = sslFactory.getSSLContext();
    socketFactory = sslContext.getSocketFactory();
    Assert.assertNotNull(socketFactory);
    serverSocketFactory = sslContext.getServerSocketFactory();
    Assert.assertNotNull(serverSocketFactory);
    SSLEngine clientSideSSLEngine = sslFactory.createSSLEngine("localhost", 9095, SSLFactory.Mode.CLIENT);
    TestSSLUtils.verifySSLConfig(sslContext, clientSideSSLEngine, true);
  }
}