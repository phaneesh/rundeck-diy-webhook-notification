/*
 * MIT License
 *
 * Copyright (c) 2018 Trevor Highfill
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.theque5t.DIYWebhookNotificationPlugin;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.RenderingOption;
import com.dtolabs.rundeck.plugins.descriptions.TextArea;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;
import liqp.Template;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.io.pem.PemReader;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants.DISPLAY_TYPE_KEY;

@Plugin(service = "Notification", name = "DIYWebhookNotificationPlugin")
@PluginDescription(title = "DIY Webhook", description = "The DIY(do it yourself) webhook notification plugin that lets you supply your own custom messages.\n___\nProject lives [here](https://github.com/theque5t/rundeck-diy-webhook-notification)\n___\n")
public class DIYWebhookNotificationPlugin implements NotificationPlugin {

  @PluginProperty(
      name = "webhookUrl",
      title = "Webhook URL",
      description = "The webhook url. Example: https://hostname/services/TXXXXXXXX/XXXXXXXXX/XXXXXXXXXXXXXXXXXXXXXXXX",
      required = false)
  @RenderingOption(key = DISPLAY_TYPE_KEY, value = "PASSWORD")
  private String webhookUrl;

  @PluginProperty(
      name = "sslCertificatePath",
      title = "SSL Certificate Path",
      description = "SSL Certificate Path used for verification/authentication.",
      required = false)
  private String sslCertificatePath;

  @PluginProperty(
      name = "contentType",
      title = "Content Type",
      description = "The content type header. Example: application/json",
      required = false)
  private String contentType;

  @PluginProperty(
      name = "messageBody",
      title = "Message Body",
      description = "The message body. Example: {\"text\":\"Hello world!\"}  \n"
          + "___  \n"
          + "#### Building the Message  \n"
          + "The execution order and summary of what occurs when building the final message body is as follows:  \n"
          + " 1. Any embedded property references will be replaced with the runtime value.  \n"
          + " 2. Any execution data references will be replaced with the runtime value.  \n"
          + " 3. Any template markup will be rendered.  \n"
          + "___  \n"
          + "#### Embedded Property References  \n"
          + "You can add [embedded property references](https://rundeck.org/docs/developer/notification-plugin.html) to your message following this syntax: `${group.key}`  \n"
          + "  \n"
          + "Example for \"On Start\": `{\"text\":\"Job ${job.name}(#${job.execid}): Started\"}`  \n"
          + "___  \n"
          + "#### Execution Data References  \n"
          + "You can add [execution data references](https://rundeck.org/docs/developer/notification-plugin.html) to your message following this syntax: `$map.key$`  \n"
          + "  \n"
          + "Examples:  \n"
          + " - `{\"text\":\"Job ${job.name}(#${job.execid}): $execution.status$\"}`  \n"
          + " - `{\"text\":\"Job ${job.name}(#$execution.id$): $execution.status$\"}`  \n"
          + " - `{\"text\":\"Job ${job.name}(#${job.execid}) from Group $execution.context.job.group$: $execution.status$\"}`  \n"
          + "  \n"
          + "[Refer here](https://rundeck.org/docs/developer/notification-plugin.html#execution-data) and [here](https://rundeck.org/docs/manual/creating-job-workflows.html#context-variables) to see what data is available.  \n"
          + "You can also supply a reference that equals an entire map. For example:  \n"
          + "```\n"
          + "{\"text\":\"Job: ${job.name} \nId: $execution.job.id$\nStatus: $execution.status$\nJob Details: $execution.job$\"}\n"
          + "```\n"
          + "  \n"
          + "___  \n"
          + "#### Template Markup  \n"
          + "You can add template markup following [the template language](https://shopify.github.io/liquid/) syntax.  \n"
          + "  \n"
          + "Example: `{\"text\":\"Job ${job.name}(#${job.execid}): {{ \"$execution.status$\" | capitalize }}\"}`  \n"
          + "  \n"
          + "Refer to the [documentation for designers](https://github.com/Shopify/liquid/wiki/Liquid-for-Designers) for further assistance with the template language.  \n"
          + "___  \n",
      required = false)
  @TextArea
  private String messageBody;

  public DIYWebhookNotificationPlugin() {

  }

  private class CustomMessageException extends Exception {
    private CustomMessageException(String message) {
      super(message);
    }
  }

  private String formatMessage(String theMessageBody, Map theExecutionData) {

    Pattern executionDataReferencePattern = Pattern.compile("\\$([a-z].*?[a-z])\\$");
    Matcher executionDataReferenceMatches = executionDataReferencePattern.matcher(theMessageBody);
    StringBuffer theMessageBodyWithExecutionDataBuffer = new StringBuffer(theMessageBody.length());

    while (executionDataReferenceMatches.find()) {
      String executionDataReferenceMatch = executionDataReferenceMatches.group(1);
      String[] executionDataReferenceCommand = executionDataReferenceMatch.split("\\.");
      Map theCurrentMap = theExecutionData;

      int i = 0;
      do {
        String theCurrentKey = executionDataReferenceCommand[i];
        i++;

        if (theCurrentMap.containsKey(theCurrentKey) && i < executionDataReferenceCommand.length) {
          theCurrentMap = (Map) theCurrentMap.get(theCurrentKey);
        } else if (theCurrentMap.containsKey(theCurrentKey)) {
          executionDataReferenceMatches.appendReplacement(theMessageBodyWithExecutionDataBuffer, theCurrentMap.get(theCurrentKey) == null ? "" : theCurrentMap.get(theCurrentKey).toString());
        } else if (theCurrentKey.equals("execution") && executionDataReferenceCommand.length == 1) {
          executionDataReferenceMatches.appendReplacement(theMessageBodyWithExecutionDataBuffer, theCurrentMap.toString());
        } else if (!theCurrentKey.equals("execution")) {
          executionDataReferenceMatches.appendReplacement(theMessageBodyWithExecutionDataBuffer, "InvalidReference-->`" + executionDataReferenceMatch + "`<--InvalidReference");
          break;
        }
      }
      while (i < executionDataReferenceCommand.length);
    }

    executionDataReferenceMatches.appendTail(theMessageBodyWithExecutionDataBuffer);
    String theMessageBodyWithExecutionData = theMessageBodyWithExecutionDataBuffer.toString();

    Template theMessageBodyAsTemplate = Template.parse(theMessageBodyWithExecutionData);
    String theNewMessage = theMessageBodyAsTemplate.render();

    return theNewMessage;
  }

  private String sendMessage(String theWebhookUrl, String theContentType, String theFormattedMessage) throws IOException, CustomMessageException {
    HttpsURLConnection connectionToWebhook = (HttpsURLConnection) new URL(theWebhookUrl).openConnection();
    connectionToWebhook.setConnectTimeout(5000);
    connectionToWebhook.setReadTimeout(5000);
    connectionToWebhook.setRequestMethod("POST");
    connectionToWebhook.setRequestProperty("Content-type", theContentType);
    connectionToWebhook.setDoOutput(true);
    if(Objects.nonNull(sslCertificatePath) && sslCertificatePath.trim().length() > 0) {
      try {
        setSocketFactory(connectionToWebhook);
      } catch(Exception e) {
        throw new IOException(e);
      }
    }
    DataOutputStream bodyOfRequest = new DataOutputStream(connectionToWebhook.getOutputStream());
    bodyOfRequest.write(theFormattedMessage.getBytes("UTF-8"));
    bodyOfRequest.flush();
    bodyOfRequest.close();

    int responseCode = connectionToWebhook.getResponseCode();
    connectionToWebhook.disconnect();

    String result = "The response code is: " + responseCode;

    if (responseCode != 200) {
      throw new CustomMessageException(result);
    }

    return result;
  }

  public boolean postNotification(String trigger, Map executionData, Map config) {

    try {
      String formattedMessage = formatMessage(messageBody, executionData);
      sendMessage(webhookUrl, contentType, formattedMessage);
    } catch (SecurityException | IllegalArgumentException | CustomMessageException | IOException e) {
      e.printStackTrace();
    }

    return true;
  }

  private SSLSocketFactory setSocketFactory(HttpsURLConnection httpsURLConnection) throws Exception {
    Security.addProvider(new BouncyCastleProvider());
    SSLContext context = SSLContext.getInstance("TLS");
    String certAndKey = new String(Files.readAllBytes(Paths.get(sslCertificatePath)));
    String delimiter = "-----END CERTIFICATE-----";
    String[] tokens = certAndKey.split(delimiter);
    byte[] certBytes = tokens[0].concat(delimiter).getBytes();
    byte[] keyBytes = tokens[1].getBytes();
    PemReader reader;
    reader = new PemReader(new InputStreamReader(new ByteArrayInputStream(certBytes)));
    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
        new ByteArrayInputStream(reader.readPemObject().getContent())
    );
    reader.close();
    reader = new PemReader(new InputStreamReader(new ByteArrayInputStream(keyBytes)));
    KeyFactory factory = KeyFactory.getInstance("RSA");
    byte[] content = reader.readPemObject().getContent();
    PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(content);
    PrivateKey key = factory.generatePrivate(privKeySpec);
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null);
    keystore.setCertificateEntry("cert-alias", cert);
    keystore.setKeyEntry("key-alias", key, "changeit".toCharArray(), new Certificate[] {cert});
    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(keystore, "changeit".toCharArray());
    KeyManager[] km = kmf.getKeyManagers();
    context.init(km, null, null);
    return context.getSocketFactory();
  }
}