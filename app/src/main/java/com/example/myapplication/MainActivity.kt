package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.not
import com.example.myapplication.R.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager

    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    private val activityScope = CoroutineScope(Dispatchers.Main)

    private lateinit var downloadSpeedTextView: TextView
    private lateinit var bandTextView: TextView
    private lateinit var cellsiteIdTextView: TextView
    private lateinit var rsrpTextView: TextView
    private lateinit var rsrqTextView: TextView // Added for RSRQ
    private lateinit var rssiTextView: TextView // Added for RSSI
    private lateinit var sinrTextView: TextView
    private lateinit var testSpeedButton: Button

    private lateinit var caInfoTextView: TextView
    private var currentSignalStrength: SignalStrength? = null

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
        private const val TEST_FILE_URL = "https://proof.ovh.net/files/10Mb.dat" // Example URL
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        downloadSpeedTextView = findViewById(id.downloadSpeedTextView)
        bandTextView = findViewById(id.bandTextView)
        cellsiteIdTextView = findViewById(id.cellsiteIdTextView)
        rsrpTextView = findViewById(id.rsrpTextView)
        rsrqTextView = findViewById(id.rsrqTextView) // Initialize RSRQ TextView
        rssiTextView = findViewById(id.rssiTextView) // Initialize RSSI TextView
        sinrTextView = findViewById(id.sinrTextView)
        testSpeedButton = findViewById(id.testSpeedButton)
        caInfoTextView = findViewById(id.caInfoTextView)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        testSpeedButton.setOnClickListener {
            downloadSpeedTextView.text = "Download Speed: Testing..."
            runSpeedTest()
        }

        checkPermissionsAndStart()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            startNetworkListener()
            updateNetworkInfo() // Initial update
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
                startNetworkListener()
                updateNetworkInfo() // Initial update
            } else {
                // Handle permission denial, e.g., show a message to the user
                bandTextView.text = "Permissions denied"
                // You might want to disable functionality or explain why permissions are needed
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startNetworkListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener,
                TelephonyCallback.SignalStrengthsListener { // Added SignalStrengthsListener for more frequent updates
                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    updateNetworkInfo()
                }

                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                override fun onSignalStrengthsChanged(signalStrengths: SignalStrength) {
                    //super.onSignalStrengthsChanged(signalStrengths)
                    updateNetworkInfo()
                }
            }
            // Register for both CellInfo and SignalStrength changes
            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback as TelephonyCallback
            )

        } else {
            @Suppress("Deprecation")
            phoneStateListener = object : PhoneStateListener() {
                // For older APIs, onCellInfoChanged might not be called as frequently
                // or might not be the primary way to get signal strength updates for all metrics.
                // onSignalStrengthsChanged is often more reliable for continuous signal updates.
                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    super.onCellInfoChanged(cellInfo)
                    updateNetworkInfo()
                }

                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    super.onSignalStrengthsChanged(signalStrength)
                    currentSignalStrength = signalStrength
                    updateNetworkInfo() // Update on signal strength change too
                }
            }
            @Suppress("Deprecation")
            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            ) // Listen for both
        }
        // Initial call to display data as soon as listener is set up (if permissions are already granted)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            updateNetworkInfo()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    private fun updateNetworkInfo() {
        try {
            val allCellInfo = telephonyManager.allCellInfo
            if (allCellInfo.isNullOrEmpty()) {
                runOnUiThread {
                    bandTextView.text = "Network Band: N/A (No cell info)"
                    cellsiteIdTextView.text = "Cell Site ID: N/A"
                    rsrpTextView.text = "RSRP: N/A"
                    rsrqTextView.text = "RSRQ: N/A"
                    rssiTextView.text = "RSSI: N/A"
                    sinrTextView.text = "SINR: N/A"
                    caInfoTextView.text = "CA: N/A"
                }
                return
            }

            val registeredCells = allCellInfo.filter { it.isRegistered }

            if (registeredCells.isNotEmpty()) {
                val primaryCell = registeredCells.first()
                when (primaryCell) {
                    is CellInfoLte -> {
                        val cellIdentity = primaryCell.cellIdentity as CellIdentityLte
                        val signalStrength = primaryCell.cellSignalStrength as CellSignalStrengthLte
                        displayLteInfo(cellIdentity, signalStrength)
                    }
                    is CellInfoNr -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cellIdentity = primaryCell.cellIdentity as CellIdentityNr
                            val signalStrength = primaryCell.cellSignalStrength as CellSignalStrengthNr
                            displayNrInfo(cellIdentity, signalStrength)
                        }
                    }
                    is CellInfoGsm -> {
                        val cellIdentity = primaryCell.cellIdentity as CellIdentityGsm
                        val signalStrength = primaryCell.cellSignalStrength as CellSignalStrengthGsm
                        displayGsmInfo(cellIdentity, signalStrength)
                    }
                }
                displayCaInfo(registeredCells)
            } else {
                displayNoSpecificCellTypeInfo()
            }
        } catch (e: SecurityException) {
            runOnUiThread {
                bandTextView.text = "Permissions error"
            }
        } catch (e: Exception) {
            runOnUiThread {
                bandTextView.text = "Error updating info"
            }
        }
    }

    private fun displayNoSpecificCellTypeInfo() {
        runOnUiThread {
            bandTextView.text = "Network Type: Unknown/Not Registered"
            cellsiteIdTextView.text = "Cell Site ID: N/A"
            rsrpTextView.text = "RSRP: N/A"
            rsrqTextView.text = "RSRQ: N/A"
            rssiTextView.text = "RSSI: N/A"
            sinrTextView.text = "SINR: N/A"
        }
    }


    private fun displayLteInfo(
        cellIdentity: CellIdentityLte,
        signalStrength: CellSignalStrengthLte
    ) {
        val band = when (val earfcn = cellIdentity.earfcn) {
            in 0..599 -> "LTE Band 1 (2100MHz)"
            in 600..1199 -> "LTE Band 2 (1900MHz)"
            in 1200..1949 -> "LTE Band 3 (1800MHz)"
            in 1950..2399 -> "LTE Band 4 (1700MHz AWS-1)"
            in 2400..2649 -> "LTE Band 5 (850MHz)"
            // No Band 6 defined for LTE FDD
            in 2750..3449 -> "LTE Band 7 (2600MHz)"
            in 3450..3799 -> "LTE Band 8 (900MHz)"
            // No Band 9 defined for LTE FDD
            // No Band 10 defined for LTE FDD
            in 4750..4949 -> "LTE Band 11 (1500MHz PDC)" // Check if this is relevant for your region
            in 5010..5179 -> "LTE Band 12 (700MHz ac)"
            in 5180..5279 -> "LTE Band 13 (700MHz c)"
            in 5280..5379 -> "LTE Band 14 (700MHz FirstNet)"
            // No Band 15, 16
            in 5730..5849 -> "LTE Band 17 (700MHz b)"
            in 5850..5999 -> "LTE Band 18 (800MHz Lower)" // Japan
            in 6000..6149 -> "LTE Band 19 (800MHz Upper)" // Japan
            in 6150..6449 -> "LTE Band 20 (800MHz DD)"
            in 6450..6599 -> "LTE Band 21 (1500MHz Upper)" // Japan
            // No Band 22
            // No Band 23
            in 7700..8039 -> "LTE Band 24 (1600MHz L-band)" // US
            in 8040..8689 -> "LTE Band 25 (1900MHz Extended PCS)"
            in 8690..9039 -> "LTE Band 26 (850MHz Extended)"
            // No Band 27
            in 9210..9659 -> "LTE Band 28 (700MHz APT)"
            in 9660..9769 -> "LTE Band 29 (700MHz SDL)" // Supplemental Downlink
            in 9770..9869 -> "LTE Band 30 (2300MHz WCS)"
            in 9870..9919 -> "LTE Band 31 (450MHz)"
            // No Band 32 (SDL)
            // TDD Bands
            in 36000..36199 -> "LTE Band 33 (1900MHz TDD)"
            in 36200..36349 -> "LTE Band 34 (2000MHz TDD)"
            in 36350..36949 -> "LTE Band 35 (1900MHz PCS TDD)"
            in 36950..37549 -> "LTE Band 36 (1900MHz PCS TDD)"
            in 37550..37749 -> "LTE Band 37 (1900MHz PCS TDD)"
            in 37750..38249 -> "LTE Band 38 (2600MHz TDD)"
            in 38250..38649 -> "LTE Band 39 (1900MHz TDD)"
            in 38650..39649 -> "LTE Band 40 (2300MHz TDD)"
            in 39650..41589 -> "LTE Band 41 (2500MHz TDD BRS/EBS)"
            in 41590..43589 -> "LTE Band 42 (3500MHz TDD)"
            in 43590..45589 -> "LTE Band 43 (3700MHz TDD)"
            in 45590..46589 -> "LTE Band 44 (700MHz APT TDD)" // aka Band 72
            in 46590..46789 -> "LTE Band 45 (1500MHz L-band TDD)"
            in 46790..54539 -> "LTE Band 46 (5200MHz unlicensed TDD)" // LAA
            in 54540..55239 -> "LTE Band 47 (5900MHz V2X)"
            in 55240..56739 -> "LTE Band 48 (3550-3700MHz CBRS TDD)"
            in 56740..58239 -> "LTE Band 49 (3500MHz TDD)" // China
            in 58240..59139 -> "LTE Band 50 (1500MHz L-band TDD)" // Europe
            in 59140..59939 -> "LTE Band 51 (1500MHz L-band TDD)" // Europe SDL
            in 59940..60139 -> "LTE Band 52 (3300-3400MHz TDD)" // China
            in 60140..61149 -> "LTE Band 53 (2483.5-2495MHz S-Band)" // Globalstar/Qualcomm
            // Common Higher Bands (often region/operator specific, check 3GPP for full list)
            in 65536..66435 -> "LTE Band 65 (2100MHz Extended FDD)"
            in 66436..67335 -> "LTE Band 66 (1700MHz Extended AWS FDD)"
            in 67336..67835 -> "LTE Band 67 (700MHz SDL)"
            in 67836..68335 -> "LTE Band 68 (700MHz FDD)"
            in 68336..68585 -> "LTE Band 69 (2600MHz SDL)"
            in 68586..68935 -> "LTE Band 70 (1700/2000MHz AWS-4 FDD)"
            in 68936..69465 -> "LTE Band 71 (600MHz FDD)"
            // Band 72 is an alias for Band 44
            in 69886..70335 -> "LTE Band 73 (450MHz FDD)" // Not widely used
            in 70336..70785 -> "LTE Band 74 (1500MHz L-Band FDD)"
            in 70786..71335 -> "LTE Band 75 (1500MHz L-Band SDL)"
            in 71336..71585 -> "LTE Band 76 (1500MHz L-Band SDL)"
            // Add more as needed, e.g. from 3GPP TS 36.101, Annex E
            else -> if (earfcn != CellInfo.UNAVAILABLE) "Unknown LTE Band (EARFCN: $earfcn)" else "Unknown LTE Band"
        }

        val pci =
            if (cellIdentity.pci != CellInfo.UNAVAILABLE) cellIdentity.pci.toString() else "N/A"
        val tac =
            if (cellIdentity.tac != CellInfo.UNAVAILABLE) cellIdentity.tac.toString() else "N/A"
        val ci = if (cellIdentity.ci != CellInfo.UNAVAILABLE) cellIdentity.ci.toString() else "N/A"
        val cellsiteIdText = "PCI: $pci, TAC: $tac, CI: $ci"


        val rsrp =
            if (signalStrength.rsrp != CellInfo.UNAVAILABLE) "${signalStrength.rsrp} dBm" else "N/A"
        val rsrq =
            if (signalStrength.rsrq != CellInfo.UNAVAILABLE) "${signalStrength.rsrq} dB" else "N/A"
        // SINR (RSSNR) is often problematic and might not be reliably available on all devices/networks.
        val sinr =
            if (signalStrength.rssnr != CellInfo.UNAVAILABLE && signalStrength.rssnr != Integer.MAX_VALUE) {
                "${signalStrength.rssnr} dB"
            } else {
                "N/A"
            }

        var rssi = "N/A"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rssi =
                if (signalStrength.rssi != CellInfo.UNAVAILABLE) "${signalStrength.rssi} dBm" else "N/A"
        }

        runOnUiThread {
            bandTextView.text = "Network: LTE $band"
            cellsiteIdTextView.text = cellsiteIdText
            rsrpTextView.text = "RSRP: $rsrp"
            rsrqTextView.text = "RSRQ: $rsrq"
            rssiTextView.text =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "RSSI: $rssi" else "RSSI: N/A (API < 29)"
            sinrTextView.text = "SINR: $sinr"
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayNrInfo(cellIdentity: CellIdentityNr, signalStrength: CellSignalStrengthNr) {
        val nrarfcn =
            if (cellIdentity.nrarfcn != CellInfo.UNAVAILABLE) cellIdentity.nrarfcn.toString() else "N/A"
        // Band determination for NR is more complex as NRARFCNs are global and don't directly map to bands like EARFCNs.
        // You'd typically need a lookup table or logic based on frequency ranges if you want to display the NR band string (e.g., "n78").
        // For now, just showing ARFCN.
        val band = "NR ARFCN: $nrarfcn" // Simplified for now

        val pci =
            if (cellIdentity.pci != CellInfo.UNAVAILABLE) cellIdentity.pci.toString() else "N/A"
        val tac =
            if (cellIdentity.tac != CellInfo.UNAVAILABLE) cellIdentity.tac.toString() else "N/A"
        val nci =
            if (cellIdentity.nci != CellInfo.UNAVAILABLE_LONG) cellIdentity.nci.toString() else "N/A"
        val cellsiteIdText = "PCI: $pci, TAC: $tac, NCI: $nci"

        val ssRsrp =
            if (signalStrength.ssRsrp != CellInfo.UNAVAILABLE) "${signalStrength.ssRsrp} dBm" else "N/A"
        val ssRsrq =
            if (signalStrength.ssRsrq != CellInfo.UNAVAILABLE) "${signalStrength.ssRsrq} dB" else "N/A"
        val ssSinr =
            if (signalStrength.ssSinr != CellInfo.UNAVAILABLE && signalStrength.ssSinr != Integer.MAX_VALUE) {
                "${signalStrength.ssSinr} dB"
            } else {
                "N/A"
            }

        // CSI values are more for beamforming and detailed channel state, might be less commonly available for basic display
        val csiRsrp =
            if (signalStrength.csiRsrp != CellInfo.UNAVAILABLE) "${signalStrength.csiRsrp} dBm" else "N/A (CSI)"
        val csiRsrq =
            if (signalStrength.csiRsrq != CellInfo.UNAVAILABLE) "${signalStrength.csiRsrq} dB" else "N/A (CSI)"
        val csiSinr =
            if (signalStrength.csiSinr != CellInfo.UNAVAILABLE && signalStrength.csiSinr != Integer.MAX_VALUE) {
                "${signalStrength.csiSinr} dB"
            } else {
                "N/A (CSI)"
            }


        runOnUiThread {
            bandTextView.text = "Network: 5G NR ($band)"
            cellsiteIdTextView.text = cellsiteIdText
            rsrpTextView.text = "SS-RSRP: $ssRsrp" // For NR, SS-RSRP is primary
            rsrqTextView.text = "SS-RSRQ: $ssRsrq"
            rssiTextView.text =
                "RSSI: N/A (NR specific)" // RSSI is less defined for NR in the same way as LTE
            sinrTextView.text = "SS-SINR: $ssSinr"
            // Optionally display CSI values if needed, perhaps in more TextViews or a details section
            // e.g., findViewById<TextView>(R.id.csiRsrpTextView).text = "CSI-RSRP: $csiRsrp"
        }
    }

    // Placeholder for GSM Info
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun displayGsmInfo(
        cellIdentity: CellIdentityGsm,
        signalStrength: CellSignalStrengthGsm
    ) {
        val lac =
            if (cellIdentity.lac != CellInfo.UNAVAILABLE) cellIdentity.lac.toString() else "N/A"
        val cid =
            if (cellIdentity.cid != CellInfo.UNAVAILABLE) cellIdentity.cid.toString() else "N/A"
        val arfcn =
            if (cellIdentity.arfcn != CellInfo.UNAVAILABLE) cellIdentity.arfcn.toString() else "N/A"
        // mcc and mnc can be obtained from cellIdentity.mccString and cellIdentity.mncString if needed

        val signalStrengthDb =
            if (signalStrength.dbm != CellInfo.UNAVAILABLE) "${signalStrength.dbm} dBm" else "N/A"
        // GSM bit error rate, timing advance could also be accessed via signalStrength if needed.

        runOnUiThread {
            bandTextView.text = "Network: GSM (ARFCN: $arfcn)"
            cellsiteIdTextView.text = "LAC: $lac, CID: $cid"
            rsrpTextView.text = "Signal: $signalStrengthDb" // GSM uses general signal strength
            rsrqTextView.text = "RSRQ: N/A (GSM)"
            rssiTextView.text = "RSSI: $signalStrengthDb" // Dbm is often used as RSSI for GSM
            sinrTextView.text = "SINR: N/A (GSM)"
        }
    }

    // Placeholder for WCDMA (UMTS) Info
    @RequiresApi(Build.VERSION_CODES.R)
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun displayWcdmaInfo(
        cellIdentity: CellIdentityWcdma,
        signalStrength: CellSignalStrengthWcdma
    ) {
        val lac =
            if (cellIdentity.lac != CellInfo.UNAVAILABLE) cellIdentity.lac.toString() else "N/A"
        val cid =
            if (cellIdentity.cid != CellInfo.UNAVAILABLE) cellIdentity.cid.toString() else "N/A"
        val uarfcn =
            if (cellIdentity.uarfcn != CellInfo.UNAVAILABLE) cellIdentity.uarfcn.toString() else "N/A"
        val psc =
            if (cellIdentity.psc != CellInfo.UNAVAILABLE) cellIdentity.psc.toString() else "N/A"

        val signalStrengthDb =
            if (signalStrength.dbm != CellInfo.UNAVAILABLE) "${signalStrength.dbm} dBm" else "N/A"
        // WCDMA also has Ec/No (EcNo) which is like SINR. signalStrength.ecNo (requires API 29 for getEcNo, older is getEcNo())

        var ecNo = "N/A"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (signalStrength.ecNo != CellInfo.UNAVAILABLE) ecNo =
                "${signalStrength.ecNo / 10.0} dB" // Often in tenths of dB
        } else {
            // No direct getEcNo before Q in CellSignalStrengthWcdma, dbm is primary
        }


        runOnUiThread {
            bandTextView.text = "Network: WCDMA (UARFCN: $uarfcn)"
            cellsiteIdTextView.text = "LAC: $lac, CID: $cid, PSC: $psc"
            rsrpTextView.text = "RSCP: $signalStrengthDb" // RSCP is often reported as dbm
            rsrqTextView.text = "Ec/No: $ecNo" // Ec/No is a quality measure
            rssiTextView.text = "RSSI: $signalStrengthDb"
            sinrTextView.text = "Ec/No: $ecNo" // Using Ec/No for SINR field for WCDMA
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayCaInfo(allCellInfo: List<CellInfo>) {
        val lteCells = allCellInfo.filterIsInstance<CellInfoLte>()

        if (lteCells.isEmpty()) {
            runOnUiThread {
                caInfoTextView.text = "CA: No LTE Cells Found"
            }
            return
        }

        val caConfig = StringBuilder()
        if (lteCells.size > 1) {
            caConfig.append("LTE CA Active: (${lteCells.size}CC)\n")
        } else {
            caConfig.append("LTE: 1CC\n")
        }

        // Sort cells to ensure primary is first, although it's usually at index 0 or is the first to be "isRegistered"
        val sortedLteCells = lteCells.sortedByDescending { it.isRegistered }

        sortedLteCells.forEachIndexed { index, cellInfo ->
            val cellIdentity = cellInfo.cellIdentity as CellIdentityLte
            val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthLte

            val status = if (cellInfo.isRegistered) "Primary (PCell)" else "Secondary (SCell)"
            val earfcn = if (cellIdentity.earfcn != CellInfo.UNAVAILABLE) cellIdentity.earfcn.toString() else "N/A"

            caConfig.append("$status: EARFCN: $earfcn")

            // The getCellBandwidths() method is the key to getting bandwidths
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28
                try {
                    // This is a direct check on the CellInfo object for bandwidth
                    val bandwidth = cellIdentity.bandwidth
                    if (bandwidth != CellInfo.UNAVAILABLE) {
                        caConfig.append(", BW: ${bandwidth / 1000}MHz")
                    }
                } catch (e: Exception) {
                    // Handle exceptions on specific devices
                }
            }

            // Add signal strength details for each carrier
            val rsrp = if (signalStrength.rsrp != CellInfo.UNAVAILABLE) "RSRP:${signalStrength.rsrp}dBm" else "RSRP:N/A"
            caConfig.append(", $rsrp\n")
        }

        runOnUiThread {
            caInfoTextView.text = caConfig.toString()
        }
    }
    private fun runSpeedTest() {
        downloadSpeedTextView.text = "Download Speed: Testing..."

        activityScope.launch(Dispatchers.IO) {
            try {
                val url = URL(TEST_FILE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 15000 // 15 seconds
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text =
                            "Download Speed: Failed (Server Error ${connection.responseCode})"
                    }
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val inputStream = connection.inputStream
                val buffer = ByteArray(8192) // Increased buffer size
                var downloadedBytes = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    downloadedBytes += bytesRead
                    // Optional: Update progress during download
                     val progress = (downloadedBytes * 100 / (100 * 1024 * 1024)).toInt() // Assuming 100MB file
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text = "Download Speed: Testing... $progress%"
                     }
                }
                inputStream.close()
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime).toDouble() / 1000.0 // Ensure double division
                connection.disconnect()

                if (duration > 0) {
                    val downloadSpeedMbps =
                        (downloadedBytes * 8.0 / (duration * 1000000.0)) // Use double for precision
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text =
                            "Download Speed: %.2f Mbps".format(downloadSpeedMbps)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text = "Download Speed: Calculation error"
                    }
                }
            } catch (e: Exception) {
                // Log.e("SpeedTest", "Error during speed test: ${e.message}", e)
                launch(Dispatchers.Main) {
                    downloadSpeedTextView.text =
                        "Download Speed: Failed (${e.javaClass.simpleName})"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel() // Cancel ongoing coroutines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("Deprecation")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }
}