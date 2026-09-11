package com.shelfj.iam.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.shelfj.ids.Ids;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The full connect-and-kick path needs a real broker (see {@link MqttSessionRevokerIT}); this just
 * covers the no-op contract that keeps logout safe when the feature isn't configured at all
 * (shelfj.notification.channel never set to mqtt anywhere) — no network call, no exception.
 */
class MqttSessionRevokerTest {

  @Test
  void revokeIsANoOpWhenNoApiCredentialsAreConfigured() {
    MqttSessionRevoker revoker = new MqttSessionRevoker();
    revoker.apiUrl = "http://localhost:18083";
    revoker.apiKey = Optional.empty();
    revoker.apiSecret = Optional.empty();

    // init() (which builds the WebClient) is deliberately never called — revoke() must return
    // before touching it, since apiKey/apiSecret are unset.
    assertDoesNotThrow(() -> revoker.revoke(Ids.newId(), Ids.newId()));
  }
}
