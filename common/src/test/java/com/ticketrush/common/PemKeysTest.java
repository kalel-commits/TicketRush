package com.ticketrush.common;

import com.ticketrush.common.security.PemKeys;
import com.ticketrush.common.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemKeysTest {

    @Test
    void readsPemPair(@TempDir Path dir) throws Exception {
        TestKeys keys = TestKeys.rsa();
        Path pub = Files.writeString(dir.resolve("pub.pem"), keys.publicPem());
        Path priv = Files.writeString(dir.resolve("priv.pem"), keys.privatePem());
        assertThat(PemKeys.publicKey(new FileSystemResource(pub)).getEncoded()).isEqualTo(keys.pub().getEncoded());
        assertThat(PemKeys.privateKey(new FileSystemResource(priv)).getEncoded()).isEqualTo(keys.priv().getEncoded());
    }

    @Test
    void missingFileFailsWithLocation() {
        assertThatThrownBy(() -> PemKeys.publicKey(new FileSystemResource("nope.pem")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nope.pem");
    }

    @Test
    void garbageFileFailsWithLocation(@TempDir Path dir) throws Exception {
        Path bad = Files.writeString(dir.resolve("bad.pem"), "hello");
        assertThatThrownBy(() -> PemKeys.publicKey(new FileSystemResource(bad)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad.pem");
    }
}
