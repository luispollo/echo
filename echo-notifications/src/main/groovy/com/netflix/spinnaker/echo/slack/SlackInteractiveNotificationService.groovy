/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.slack

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.echo.api.Notification
import com.netflix.spinnaker.echo.notification.InteractiveNotificationService
import com.netflix.spinnaker.echo.notification.NotificationTemplateEngine
import com.netflix.spinnaker.kork.web.exceptions.InvalidRequestException
import com.netflix.spinnaker.retrofit.Slf4jRetrofitLogger
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.util.UriComponentsBuilder
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.client.Response
import retrofit.converter.JacksonConverter
import retrofit.http.Body
import retrofit.http.POST
import retrofit.http.Path

import static retrofit.Endpoints.newFixedEndpoint

@Slf4j
@Component
@ConditionalOnProperty('slack.enabled')
class SlackInteractiveNotificationService extends SlackNotificationService implements InteractiveNotificationService {
  private final static String SLACK_WEBHOOK_BASE_URL = "https://hooks.slack.com"

  private SlackAppService slackAppService
  private SlackHookService slackHookService
  private ObjectMapper objectMapper

  @Autowired
  SlackInteractiveNotificationService(
    @Qualifier("slackAppService") SlackAppService slackAppService,
    NotificationTemplateEngine notificationTemplateEngine,
    Client retrofitClient,
    ObjectMapper objectMapper
  ) {
    super(slackAppService, notificationTemplateEngine)
    this.slackAppService = slackAppService as SlackAppService
    this.objectMapper = objectMapper
    this.slackHookService = getSlackHookService(retrofitClient)
  }

  // For access from tests only
  SlackInteractiveNotificationService(
    @Qualifier("slackAppService") SlackAppService slackAppService,
    SlackHookService slackHookService,
    NotificationTemplateEngine notificationTemplateEngine,
    ObjectMapper objectMapper
  ) {
    super(slackAppService, notificationTemplateEngine)
    this.slackAppService = slackAppService as SlackAppService
    this.objectMapper = objectMapper
    this.slackHookService = slackHookService
  }

  @Override
  Notification.InteractionCallback parseInteractionCallback(RequestEntity<String> request) {
    // Before anything else, verify the signature on the request
    slackAppService.verifySignature(request)

    MultiValueMap<String, String> parameters = getParametersFromBody(request)

    if (parameters.payload) {
      return parseInteractiveActionCallback(parameters.payload)
    } else if (parameters.command && !parameters.command.isEmpty()) {
      return parseCommandCallback(parameters)
    }
  }

  MultiValueMap<String, String> getParametersFromBody(RequestEntity<String> request) {
    String body = URLDecoder.decode(request.body, "UTF-8")
    return UriComponentsBuilder.fromUriString("http://fake/?${body}").build().getQueryParams()
  }

  Notification.CommandCallback parseCommandCallback(MultiValueMap<String, String> parameters) {
    new Notification.CommandCallback(
      user: parameters.user_name[0],
      command: parameters.command[0],
      arguments: parameters.text ? parameters.text[0] : ""
    )
  }

  private Notification.InteractionCallback parseInteractiveActionCallback(String payloadJson) {
    Map payload = parseSlackPayload(payloadJson)
    log.debug("Received callback event from Slack of type ${payload.type}")

    if (payload.actions.size > 1) {
      log.warn("Expected a single selected action from Slack, but received ${payload.actions.size}")
    }

    if (payload.actions[0].type != "button") {
      throw new InvalidRequestException("Spinnaker currently only supports Slack button actions.")
    }

    def (serviceId, callbackId) = payload.callback_id.split(":")

    String user = payload.user.name
    try {
      SlackService.SlackUserInfo userInfo = slackAppService.getUserInfo(payload.user.id)
      user = userInfo.email
    } catch (Exception e) {
      log.error("Error retrieving info for Slack user ${payload.user.name} (${payload.user.id}). Falling back to username.")
    }

    new Notification.InteractiveActionCallback(
      serviceId: serviceId,
      messageId: callbackId,
      user: user,
      actionPerformed: new Notification.ButtonAction(
        name: payload.actions[0].name,
        label: payload.actions[0].text,
        value: payload.actions[0].value
      )
    )
  }

  @Override
  Optional<ResponseEntity<String>> respondToCallback(RequestEntity<String> request, Response downstreamResponse) {
    String body = request.getBody()

    MultiValueMap<String, String> parameters = getParametersFromBody(request)

    if (parameters.command && !parameters.command.isEmpty()) {
      if (downstreamResponse.body.length() > 0) {
        String bodyAsString = new InputStreamReader(downstreamResponse.body.in()).readLines().join("\n")
        return Optional.of(
          ResponseEntity
            .status(downstreamResponse.status)
            .body(blocks: [[type: "section", text: [type: "mrkdwn", text: bodyAsString]]])
        )
      }
      return Optional.empty()
    }

    Map payload = parseSlackPayload(body)
    log.debug("Responding to Slack callback via ${payload.response_url}")

    def selectedAction = payload.actions[0]
    def attachment = payload.original_message.attachments[0] // we support a single attachment as per Echo notifications
    def selectedActionText = attachment.actions.stream().find {
      it.type == selectedAction.type && it.value == selectedAction.value
    }.text

    Map message = [:]
    message.putAll(payload.original_message)
    message.attachments[0].remove("actions")
    message.attachments[0].text += "\n\nUser <@${payload.user.id}> clicked the *${selectedActionText}* action."

    // Example: https://hooks.slack.com/actions/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX
    URI responseUrl = new URI(payload.response_url)
    Response response = slackHookService.respondToMessage(responseUrl.path, message)
    log.debug("Response from Slack: ${response.toString()}")

    return Optional.empty()
  }

  private Map parseSlackPayload(String body) {
    if (!body.startsWith("payload=")) {
      throw new InvalidRequestException("Missing payload field in Slack callback request.")
    }

    Map payload = objectMapper.readValue(
      // Slack requests use application/x-www-form-urlencoded
      URLDecoder.decode(body.split("payload=")[1], "UTF-8"),
      Map)

    // currently supporting only interactive actions
    if (payload.type != "interactive_message") {
      throw new InvalidRequestException("Unsupported Slack callback type: ${payload.type}")
    }

    if (!payload.callback_id || !payload.user?.name) {
      throw new InvalidRequestException("Slack callback_id and user not present. Cannot route the request to originating Spinnaker service.")
    }

    payload
  }

  private SlackHookService getSlackHookService(Client retrofitClient) {
    log.info("Slack hook service loaded")
    new RestAdapter.Builder()
      .setEndpoint(newFixedEndpoint(SLACK_WEBHOOK_BASE_URL))
      .setClient(retrofitClient)
      .setLogLevel(RestAdapter.LogLevel.BASIC)
      .setLog(new Slf4jRetrofitLogger(SlackHookService.class))
      .setConverter(new JacksonConverter())
      .build()
      .create(SlackHookService.class)
  }

  interface SlackHookService {
    @POST('/{path}')
    Response respondToMessage(@Path(value = "path", encode = false) path, @Body Map content)
  }
}
