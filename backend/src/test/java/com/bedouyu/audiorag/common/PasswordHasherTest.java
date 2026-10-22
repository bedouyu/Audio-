package com.bedouyu.audiorag.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    @Test void derivedValuesAreSaltedAndDifferForTheSamePassword() {
        String first = PasswordHasher.hash("correct horse battery staple");
        String second = PasswordHasher.hash("correct horse battery staple");

        assertThat(first).startsWith("pbkdf2$210000$").isNotEqualTo(second);
        assertThat(PasswordHasher.verify("correct horse battery staple", first)).isTrue();
        assertThat(PasswordHasher.verify("correct horse battery staple", second)).isTrue();
    }

    @Test void wrongPasswordAndDamagedRecordsAreRejectedWithoutThrowing() {
        String stored = PasswordHasher.hash("s3cret-pass");

        assertThat(PasswordHasher.verify("s3cret-pas", stored)).isFalse();
        assertThat(PasswordHasher.verify("s3cret-pass", "pbkdf2$x$y$z")).isFalse();
        assertThat(PasswordHasher.verify("s3cret-pass", "pbkdf2$210000$!!!$???")).isFalse();
        assertThat(PasswordHasher.verify("s3cret-pass", "")).isFalse();
        assertThat(PasswordHasher.verify("s3cret-pass", null)).isFalse();
        assertThat(PasswordHasher.verify(null, stored)).isFalse();
    }

    @Test void legacyUnsaltedSha256StillVerifiesButIsFlaggedForUpgrade() {
        String legacy = Hashing.sha256("old-password");

        assertThat(legacy).hasSize(64);
        assertThat(PasswordHasher.verify("old-password", legacy)).isTrue();
        assertThat(PasswordHasher.verify("other-password", legacy)).isFalse();
        assertThat(PasswordHasher.needsUpgrade(legacy)).isTrue();
        assertThat(PasswordHasher.needsUpgrade(PasswordHasher.hash("old-password"))).isFalse();
    }

    @Test void iterationsAreReadFromTheStoredValueSoOldRecordsKeepWorking() {
        String stored = PasswordHasher.hash("keep-me");
        String[] parts = stored.split("\\$");

        String rewritten = "pbkdf2$" + Integer.parseInt(parts[1]) + "$" + parts[2] + "$" + parts[3];
        assertThat(PasswordHasher.verify("keep-me", rewritten)).isTrue();
        assertThat(PasswordHasher.verify("keep-me", stored.replace("$210000$", "$1000$"))).isFalse();
    }
}
