package dev.pi.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsSigV4Test {
    private val credentials = AwsCredentials(
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    )

    private fun signatureOf(authorization: String): String =
        authorization.substringAfter("Signature=")

    @Test
    fun formatsAmzDateInUtc() {
        val dateTime = AwsSigV4.formatAmzDate(1440938160000L)
        assertEquals("20150830", dateTime.date)
        assertEquals("20150830T123600Z", dateTime.timestamp)
    }

    @Test
    fun signsAwsDocumentedIamExample() {
        val signed = AwsSigV4.sign(
            method = "GET",
            url = "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
            headers = mapOf(
                "content-type" to "application/x-www-form-urlencoded; charset=utf-8",
                "host" to "iam.amazonaws.com",
            ),
            payload = ByteArray(0),
            credentials = credentials,
            region = "us-east-1",
            service = "iam",
            epochMillis = 1440938160000L,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
            signed.headers["Authorization"],
        )
        assertEquals("20150830T123600Z", signed.headers["x-amz-date"])
    }

    @Test
    fun signsAwsSigV4TestSuiteVanillaRequest() {
        val signed = AwsSigV4.sign(
            method = "GET",
            url = "https://example.amazonaws.com/",
            headers = mapOf("host" to "example.amazonaws.com"),
            payload = ByteArray(0),
            credentials = credentials,
            region = "us-east-1",
            service = "service",
            epochMillis = 1440938160000L,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun signsBedrockArnModelPath() {
        val path = "/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile" +
            "%2Fus.anthropic.claude-sonnet-4-5-v1%3A0/converse-stream"
        val signed = AwsSigV4.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com$path",
            headers = mapOf(
                "content-type" to "application/json",
                "accept" to "application/vnd.amazon.eventstream",
            ),
            payload = """{"messages":[]}""".encodeToByteArray(),
            credentials = credentials,
            region = "us-east-1",
            service = AwsSigV4.BEDROCK_SERVICE,
            epochMillis = 1767225600000L,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260101/us-east-1/bedrock/aws4_request, " +
                "SignedHeaders=accept;content-type;host;x-amz-date, " +
                "Signature=accf361f62c6a53acce02b0c6f6e3bce53c6db5e6bac424a58ea438183d7ad6b",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun signsSessionTokenHeaders() {
        val signed = AwsSigV4.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test-model/converse-stream",
            headers = mapOf("content-type" to "application/json"),
            payload = ByteArray(0),
            credentials = credentials.copy(sessionToken = "SESSIONTOKEN"),
            region = "us-east-1",
            service = AwsSigV4.BEDROCK_SERVICE,
            epochMillis = 1767225600000L,
        )
        assertEquals("SESSIONTOKEN", signed.headers["x-amz-security-token"])
        val authorization = signed.headers.getValue("Authorization")
        assertTrue(authorization.contains("x-amz-security-token"))
        assertTrue(signatureOf(authorization).length == 64)
    }

    @Test
    fun percentEncodesAwsLabelSegments() {
        assertEquals(
            "arn%3Aaws%3Abedrock%3Aus-east-1%3A1%3Ainference-profile%2Fmodel%3A0",
            AwsSigV4.awsPercentEncode("arn:aws:bedrock:us-east-1:1:inference-profile/model:0"),
        )
        assertEquals("a%20b~c_d.e-f", AwsSigV4.awsPercentEncode("a b~c_d.e-f"))
        assertEquals("claude-3-5", AwsSigV4.awsPercentEncode("claude-3-5"))
    }

    @Test
    fun payloadHashIsLowercaseSha256Hex() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AwsSigV4.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun hmacSha256MatchesRfc4231TestCaseOne() {
        val key = ByteArray(20) { 0x0b }
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        assertEquals(expected, AwsSigV4.toHex(AwsSigV4.hmacSha256(key, "Hi There".encodeToByteArray())))
    }
}
