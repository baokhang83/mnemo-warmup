package io.github.baokhang83.mnemo.warmup.core;

/** Where temporary AWS keys come from (web identity today; Pod Identity and others plug in here). */
interface AwsCredentialsSource {

    /** Fresh keys. Throws {@link StoreException} naming the step that failed, never printing a secret. */
    AwsCredentials resolve();
}
