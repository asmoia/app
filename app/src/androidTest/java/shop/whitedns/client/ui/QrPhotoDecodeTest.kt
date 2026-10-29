package shop.whitedns.client.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.exportStormDnsProfileLink
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrPhotoDecodeTest {
    @Test fun fullCameraPhotoDecodesExportedProfileAndRejectsInvalidImages() {
        val profile = ConnectionProfile(id = "qr-photo-review", name = "QR photo round trip", customServerDomain = "review.invalid",
            customServerEncryptionKey = "00112233445566778899aabbccddeeff")
        val link = WhiteDnsSettings().exportStormDnsProfileLink(profile)
        val size = 1200
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
        val qr = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val photo = Bitmap.createBitmap(2400, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo)
        val file = File.createTempFile("qr-photo-test-", ".jpg",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(qr, 600f, 600f, null)
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            assertEquals(link, decodeQrPhoto(file).getOrThrow())
            canvas.drawColor(Color.WHITE)
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            assertTrue(decodeQrPhoto(file).isFailure)
            file.writeText("not an image")
            assertTrue(decodeQrPhoto(file).isFailure)
        } finally {
            file.delete()
            qr.recycle()
            photo.recycle()
        }
    }
}
