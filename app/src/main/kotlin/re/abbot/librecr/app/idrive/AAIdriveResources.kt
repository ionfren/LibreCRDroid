package re.abbot.librecr.app.idrive

import android.content.Context
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import io.bimmergestalt.idriveconnectkit.android.security.CertMangling
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess

/** Read installed AAIdrive assets without executing its code or distributing its OEM resources. */
internal class AAIdriveResources(context: Context, private val brand: String) {
    companion object {
        const val PACKAGE = "me.hufman.androidautoidrive"
    }

    private val assets = CarAppAssetResources(context.createPackageContext(PACKAGE, 0), "smartthings")
    private val rawCertificate = requireNotNull(assets.getAppCertificateRaw(brand)) { "AAIdrive certificate missing" }
        .use { it.readBytes() }

    fun certificate(security: SecurityAccess): ByteArray =
        CertMangling.mergeBMWCert(rawCertificate, security.fetchBMWCerts(brandHint = brand))

    val description: ByteArray = requireNotNull(assets.getUiDescription()) { "AAIdrive layout missing" }
        .use { it.readBytes() }

    val images: ByteArray = requireNotNull(assets.getImagesDB(brand)) { "AAIdrive images missing" }
        .use { it.readBytes() }
}
