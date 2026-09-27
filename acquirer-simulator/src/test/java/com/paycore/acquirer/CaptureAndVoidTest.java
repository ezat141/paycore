package com.paycore.acquirer;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaptureAndVoidTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void capturesAnApprovedAuthorizationAndRepeatingItIsHarmless() throws Exception {
        String id = approved();

        action(id, "capture")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationId").value(id))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        action(id, "capture")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void voidsAnApprovedAuthorizationAndRepeatingItIsHarmless() throws Exception {
        String id = approved();

        action(id, "void").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        action(id, "void").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void aCapturedAuthorizationCannotBeVoided() throws Exception {
        String id = approved();
        action(id, "capture").andExpect(status().isOk());

        action(id, "void").andExpect(status().isConflict());

        // The rejected move must not have disturbed the state it rejected.
        action(id, "capture").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void aVoidedAuthorizationCannotBeCaptured() throws Exception {
        String id = approved();
        action(id, "void").andExpect(status().isOk());

        action(id, "capture").andExpect(status().isConflict());

        // The rejected move must not have disturbed the state it rejected.
        action(id, "void").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void aDeclinedAuthorizationCannotBeCaptured() throws Exception {
        String id = authorizationId(authorize("tok_decline_funds"));

        action(id, "capture").andExpect(status().isConflict());
    }

    @Test
    void aDeclinedAuthorizationCannotBeVoided() throws Exception {
        String id = authorizationId(authorize("tok_decline_funds"));

        action(id, "void").andExpect(status().isConflict());
    }

    /**
     * Boot renders an unmapped route's 404 the same way as ours, so asserting the status alone
     * can't tell the two apart. Asserting which handler served the request rules out the
     * unmapped-route false positive.
     */
    @ParameterizedTest
    @MethodSource("actionsAndHandlerMethods")
    void anUnknownAuthorizationIsNotFound(String action, String handlerMethod) throws Exception {
        action("auth_does_not_exist", action)
                .andExpect(status().isNotFound())
                .andExpect(handler().handlerType(AuthorizationController.class))
                .andExpect(handler().methodName(handlerMethod));
    }

    private static Stream<Arguments> actionsAndHandlerMethods() {
        return Stream.of(
                Arguments.of("capture", "capture"),
                Arguments.of("void", "voidAuthorization"));
    }

    private String approved() throws Exception {
        return authorizationId(authorize("tok_approve"));
    }

    private ResultActions authorize(String cardToken) throws Exception {
        return mockMvc.perform(post("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","amount":1999,"currency":"USD","cardToken":"%s"}"""
                        .formatted(UUID.randomUUID(), cardToken)));
    }

    private ResultActions action(String authorizationId, String action) throws Exception {
        return mockMvc.perform(post("/authorizations/{id}/{action}", authorizationId, action));
    }

    private static String authorizationId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.authorizationId");
    }
}
