/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.auth.config.MethodInfo;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.util.Clock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.UncheckedExecutionException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

public final class IntegrationTest {

  private static final String JWKS_PATH = "jwks";
  private static final String X509_PATH = "x509";

  private static final int TEST_SERVER_PORT = 8765;
  private static final String ISSUER = "https://localhost" + ":" + TEST_SERVER_PORT;
  private static final String TEST_SERVER_URL = ISSUER;

  private static final String KEY_ID = "key-id";
  private static final RsaJsonWebKey RSA_JSON_WEB_KEY = TestUtils.generateRsaJsonWebKey(KEY_ID);

  private static final Set<String> AUDIENCES = ImmutableSet.of("aud1", "aud2");
  private static final String EMAIL = "user@localhost.com";
  private static final String SERVICE_NAME = "service-name";
  private static final String SUBJECT = "subject-id";
  private static final Map<String, Set<String>> ISSUER_AUDIENCES =
      ImmutableMap.of(ISSUER, AUDIENCES);

  private static final IntegrationTestServer server =
      new IntegrationTestServer(TEST_SERVER_PORT, Resource.class);

  private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
  private final MethodInfo methodInfo = new MethodInfo(ISSUER_AUDIENCES);

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // Set the path to the trustStore.
    URL truststoreUrl = IntegrationTest.class.getClassLoader().getResource("truststore.jks");
    System.setProperty("javax.net.ssl.trustStore", truststoreUrl.getPath());

    server.start();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    server.stop();
  }

  @Test
  public void testVerifyAuthTokenWithJwks() {
    GenericUrl keyUrl = new GenericUrl(TEST_SERVER_URL + "/" + JWKS_PATH);
    IssuerKeyUrlConfig issuerKeyUrlConfig = new IssuerKeyUrlConfig(false, Optional.of(keyUrl));
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL), Optional.of(ISSUER),
        Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    UserInfo userInfo = authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
    examineUserInfo(userInfo);
  }

  @Test
  public void testExtractAuthTokenFromUrlParameters() {
    GenericUrl keyUrl = new GenericUrl(TEST_SERVER_URL + "/" + JWKS_PATH);
    IssuerKeyUrlConfig issuerKeyUrlConfig = new IssuerKeyUrlConfig(false, Optional.of(keyUrl));
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL), Optional.of(ISSUER),
        Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);
    when(httpRequest.getParameter("access_token")).thenReturn(authToken);
    UserInfo userInfo = authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
    examineUserInfo(userInfo);
  }

  @Test
  public void testVerifyAuthTokenWithX509Certificates() {
    GenericUrl keyUrl = new GenericUrl(TEST_SERVER_URL + "/" + X509_PATH);
    IssuerKeyUrlConfig issuerKeyUrlConfig = new IssuerKeyUrlConfig(false, Optional.of(keyUrl));
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL), Optional.of(ISSUER),
        Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    UserInfo userInfo = authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
    examineUserInfo(userInfo);
  }

  @Test
  public void testOpenIdDiscovery() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(true, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL), Optional.of(ISSUER),
        Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    UserInfo userInfo = authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
    examineUserInfo(userInfo);
  }

  @Test
  public void testFailedOpenIdDiscovery() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(false, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL), Optional.of(ISSUER),
        Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UncheckedExecutionException exception) {
      assertTrue(ExceptionUtils.getRootCause(exception) instanceof UnauthenticatedException);
    }
  }

  @Test
  public void testAuthenticateWithMalformedJwt() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(false, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer malformed-jwt-token");
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UncheckedExecutionException exception) {
      assertTrue(ExceptionUtils.getRootCause(exception) instanceof JoseException);
    }
  }

  @Test
  public void testAuthenticateWithUnknownIssuer() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(false, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL),
        Optional.of("https://unknown.issuer.com"), Optional.of(SUBJECT),
        RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UncheckedExecutionException exception) {
      Throwable rootCause = ExceptionUtils.getRootCause(exception);
      assertTrue(rootCause instanceof UnauthenticatedException);
      assertTrue(rootCause.getMessage().contains("the issuer is unknown"));
    }
  }

  @Test
  public void testAuthenticateWithInvalidAudience() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(true, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);

    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(ImmutableSet.of("disallowed-audience")),
        Optional.of(EMAIL), Optional.of(ISSUER), Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UnauthenticatedException exception) {
      assertEquals("Audiences not allowed", exception.getMessage());
    }
  }

  @Test
  public void testAuthenticateWithExpiredAuthToken() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(true, Optional.<GenericUrl>absent());
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    TestingClock testingClock = new TestingClock();
    Authenticator authenticator = createAuthenticator(testingClock, issuerKeyUrls);

    long currentTimeMillis = System.currentTimeMillis();
    long fiveMinutesLater = currentTimeMillis + TimeUnit.MINUTES.toMillis(5);
    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES),
        Optional.of(EMAIL),
        NumericDate.fromMilliseconds(fiveMinutesLater),
        Optional.of(ISSUER),
        NumericDate.fromMilliseconds(currentTimeMillis),
        Optional.of(SUBJECT),
        RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);

    // First make sure the auth token is not expired initially.
    testingClock.setCurrentTime(currentTimeMillis);
    authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);

    // Now advance the clock to make sure that an expired auth token is properly handled.
    testingClock.setCurrentTime(fiveMinutesLater + TimeUnit.MINUTES.toMillis(5));
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UnauthenticatedException exception) {
      assertEquals("The auth token has already expired", exception.getMessage());
    }
  }

  @Test
  public void testInvalidOpenIdDiscoveryUrl() {
    IssuerKeyUrlConfig issuerKeyUrlConfig = new
        IssuerKeyUrlConfig(true, Optional.<GenericUrl>absent());
    String issuer = "https://invalid.issuer";
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(issuer, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);
    String authToken = TestUtils.generateAuthToken(
        Optional.<Collection<String>>of(AUDIENCES), Optional.of(EMAIL),
        Optional.of(issuer), Optional.of(SUBJECT), RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UncheckedExecutionException exception) {
      assertTrue(ExceptionUtils.getRootCause(exception) instanceof UnknownHostException);
    }
  }

  @Test
  public void testInvalidJwksUri() {
    IssuerKeyUrlConfig issuerKeyUrlConfig =
        new IssuerKeyUrlConfig(false, Optional.of(new GenericUrl("https://invalid.jwks.uri")));
    Map<String, IssuerKeyUrlConfig> issuerKeyUrls = ImmutableMap.of(ISSUER, issuerKeyUrlConfig);
    Authenticator authenticator = createAuthenticator(Clock.SYSTEM, issuerKeyUrls);
    String authToken = TestUtils.generateAuthToken(Optional.<Collection<String>>of(AUDIENCES),
        Optional.of(EMAIL), Optional.of(ISSUER), Optional.of(SUBJECT),
        RSA_JSON_WEB_KEY);
    when(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + authToken);
    try {
      authenticator.authenticate(httpRequest, methodInfo, SERVICE_NAME);
      fail();
    } catch (UncheckedExecutionException exception) {
      assertTrue(ExceptionUtils.getRootCause(exception) instanceof UnknownHostException);
    }
  }

  private static Authenticator createAuthenticator(
      Clock clock, Map<String, IssuerKeyUrlConfig> issuerKeyUrls) {
    HttpRequestFactory httpRequestFactory = new NetHttpTransport().createRequestFactory();
    DefaultKeyUriSupplier keyUriSupplier =
        new DefaultKeyUriSupplier(httpRequestFactory, issuerKeyUrls);
    JwksSupplier defaultJwksSupplier = new DefaultJwksSupplier(httpRequestFactory, keyUriSupplier);
    JwksSupplier jwksSupplier = new CachingJwksSupplier(defaultJwksSupplier);
    AuthTokenVerifier authTokenVerifier = new DefaultAuthTokenVerifier(jwksSupplier);
    AuthTokenDecoder defaultDecoder = new DefaultAuthTokenDecoder(authTokenVerifier);
    AuthTokenDecoder cachingDecoder = new CachingAuthTokenDecoder(defaultDecoder);
    return new Authenticator(cachingDecoder, clock);
  }

  private static void examineUserInfo(UserInfo userInfo) {
    assertEquals(AUDIENCES, userInfo.getAudiences());
    assertEquals(EMAIL, userInfo.getEmail());
    assertEquals(ISSUER, userInfo.getIssuer());
    assertEquals(SUBJECT, userInfo.getId());
  }

  @Path("/")
  @Produces(MediaType.APPLICATION_JSON)
  public static final class Resource {
    private static final ObjectWriter WRITER = new ObjectMapper().writer();

    /**
     * Return the serialized JSON of a JSON web key set.
     */
    @GET
    @Path(JWKS_PATH)
    public String getJsonWebKeySet() {
      // Generate a random JWK to make sure the correct key is selected when
      // verifying the signature.
      RsaJsonWebKey randomRsaJsonWebKey = TestUtils.generateRsaJsonWebKey("random-key");
      return new JsonWebKeySet(randomRsaJsonWebKey, RSA_JSON_WEB_KEY).toJson();
    }

    /**
     * Return the serialized JSON of a map mapping from key IDs to the
     * corresponding PEM-encoded X509 certificates.
     */
    @GET
    @Path(X509_PATH)
    public String getX509Certificates() throws JsonProcessingException {
      // Generate a random JWK to make sure the correct key is selected when
      // verifying the signature.
      RsaJsonWebKey randomRsaJsonWebKey = TestUtils.generateRsaJsonWebKey("random-key");
      Map<String, String> x509Certificates = ImmutableMap.of(
          randomRsaJsonWebKey.getKeyId(), TestUtils.generateX509Cert(randomRsaJsonWebKey),
          RSA_JSON_WEB_KEY.getKeyId(), TestUtils.generateX509Cert(RSA_JSON_WEB_KEY));
      return WRITER.writeValueAsString(x509Certificates);
    }

    @GET
    @Path(".well-known/openid-configuration")
    public String getOpenIdConfiguration() throws JsonProcessingException {
      String jwksUrl = TEST_SERVER_URL + "/" + JWKS_PATH;
      return WRITER.writeValueAsString(ImmutableMap.of("jwks_uri", jwksUrl));
    }
  }

  private static final class TestingClock implements Clock {
    private long currentTime;

    @Override
    public long currentTimeMillis() {
      return this.currentTime;
    }

    public void setCurrentTime(long currentTime) {
      this.currentTime = currentTime;
    }
  }
}
