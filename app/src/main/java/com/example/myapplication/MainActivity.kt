package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R.*
import data.NetworkSession
import data.RfAndCaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager

    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    private val activityScope = CoroutineScope(Dispatchers.Main)

    private lateinit var networkNameTextView: TextView
    private lateinit var downloadSpeedTextView: TextView
    private lateinit var bandTextView: TextView
    private lateinit var cellsiteIdTextView: TextView
    private lateinit var rsrpTextView: TextView
    private lateinit var rsrqTextView: TextView
    private lateinit var rssiTextView: TextView
    private lateinit var sinrTextView: TextView
    private lateinit var testSpeedButton: Button
    private lateinit var caInfoTextView: TextView

    private lateinit var mainExecutor: Executor


    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
        private const val TEST_FILE_URL = "https://proof.ovh.net/files/10Mb.dat"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        networkNameTextView = findViewById(id.networkNameTextView)
        downloadSpeedTextView = findViewById(id.downloadSpeedTextView)
        bandTextView = findViewById(id.bandTextView)
        cellsiteIdTextView = findViewById(id.cellsiteIdTextView)
        rsrpTextView = findViewById(id.rsrpTextView)
        rsrqTextView = findViewById(id.rsrqTextView)
        rssiTextView = findViewById(id.rssiTextView)
        sinrTextView = findViewById(id.sinrTextView)
        testSpeedButton = findViewById(id.testSpeedButton)
        caInfoTextView = findViewById(id.caInfoTextView)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        mainExecutor = ContextCompat.getMainExecutor(this)


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
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
                startNetworkListener()
            } else {
                bandTextView.text = "Permissions denied"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    private fun startNetworkListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    updateNetworkInfo()
                }
            }
            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback as TelephonyCallback
            )

        } else {
            @Suppress("Deprecation")
            phoneStateListener = object : PhoneStateListener() {
                @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    super.onCellInfoChanged(cellInfo)
                    updateNetworkInfo()
                }
            }
            @Suppress("Deprecation")
            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CELL_INFO
            )
        }
        updateNetworkInfo()
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    private fun updateNetworkInfo() {
        try {
            val allCellInfo = telephonyManager.allCellInfo

            val networkName = telephonyManager.networkOperatorName
            runOnUiThread {
                networkNameTextView.text = "Network: $networkName"
            }

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

            val registeredCellInfo = allCellInfo.firstOrNull { it.isRegistered }

            if (registeredCellInfo != null) {
                when (registeredCellInfo) {
                    is CellInfoLte -> {
                        val cellIdentity = registeredCellInfo.cellIdentity as CellIdentityLte
                        val signalStrength = registeredCellInfo.cellSignalStrength as CellSignalStrengthLte
                        displayLteInfo(cellIdentity, signalStrength)
                        displayCaInfo(allCellInfo)
                    }
                    is CellInfoNr -> {
                        val cellIdentity = registeredCellInfo.cellIdentity as CellIdentityNr
                        val signalStrength = registeredCellInfo.cellSignalStrength as CellSignalStrengthNr
                        displayNrInfo(cellIdentity, signalStrength)
                        displayCaInfo(allCellInfo)
                    }
                    else -> displayNoSpecificCellTypeInfo()
                }
            } else {
                displayNoSpecificCellTypeInfo()
            }
        } catch (e: SecurityException) {
            runOnUiThread {
                bandTextView.text = "Permissions error"
            }
        } catch (e: Exception) {
            runOnUiThread {
                bandTextView.text = "Error updating info: ${e.message}"
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
            caInfoTextView.text = "CA: N/A"
        }
    }

    private fun displayLteInfo(
        cellIdentity: CellIdentityLte,
        signalStrength: CellSignalStrengthLte
    ) {
        val earfcn = cellIdentity.earfcn
        val band = when (earfcn) {
            in 0..599 -> "LTE Band 1 (2100MHz)"
            in 600..1199 -> "LTE Band 2 (1900MHz)"
            in 1200..1949 -> "LTE Band 3 (1800MHz)"
            in 1950..2399 -> "LTE Band 4 (1700MHz AWS-1)"
            in 2400..2649 -> "LTE Band 5 (850MHz)"
            in 2750..3449 -> "LTE Band 7 (2600MHz)"
            in 3450..3799 -> "LTE Band 8 (900MHz)"
            in 5010..5179 -> "LTE Band 12 (700MHz ac)"
            in 5180..5279 -> "LTE Band 13 (700MHz c)"
            in 5280..5379 -> "LTE Band 14 (700MHz FirstNet)"
            in 5730..5849 -> "LTE Band 17 (700MHz b)"
            in 5850..5999 -> "LTE Band 18 (800MHz Lower)"
            in 6000..6149 -> "LTE Band 19 (800MHz Upper)"
            in 6150..6449 -> "LTE Band 20 (800MHz DD)"
            in 9210..9659 -> "LTE Band 28 (700MHz APT)"
            in 38650..39649 -> "LTE Band 40 (2300MHz TDD)"
            in 39650..41589 -> "LTE Band 41 (2500MHz TDD BRS/EBS)"
            else -> "Unknown LTE Band (EARFCN: $earfcn)"
        }

        val pci = if (cellIdentity.pci != CellInfo.UNAVAILABLE) cellIdentity.pci.toString() else "N/A"
        val tac = if (cellIdentity.tac != CellInfo.UNAVAILABLE) cellIdentity.tac.toString() else "N/A"
        val ci = if (cellIdentity.ci != CellInfo.UNAVAILABLE) cellIdentity.ci.toString() else "N/A"
        val cellsiteIdText = "PCI: $pci, TAC: $tac, CI: $ci"

        val rsrpValue = signalStrength.rsrp
        val rsrp = if (rsrpValue != CellInfo.UNAVAILABLE) "$rsrpValue dBm" else "N/A"

        val rsrqValue = signalStrength.rsrq
        val rsrq = if (rsrqValue != CellInfo.UNAVAILABLE) "$rsrqValue dB" else "N/A"

        val sinrValue = signalStrength.rssnr
        val sinr =
            if (sinrValue != CellInfo.UNAVAILABLE && sinrValue != Integer.MAX_VALUE) "$sinrValue dB" else "N/A"

        val rssiValue =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signalStrength.rssi else CellInfo.UNAVAILABLE
        val rssi = if (rssiValue != CellInfo.UNAVAILABLE) "$rssiValue dBm" else "N/A"

        runOnUiThread {
            bandTextView.text = "Network: LTE ($band)"
            cellsiteIdTextView.text = cellsiteIdText

            rsrpTextView.text = "RSRP: $rsrp"
            if (rsrpValue != CellInfo.UNAVAILABLE && rsrpValue != Integer.MAX_VALUE) {
                val rsrpColor = getColorForSignal(rsrpValue, -120, -80)
                rsrpTextView.setTextColor(rsrpColor)
            } else {
                rsrpTextView.setTextColor(Color.BLACK)
            }

            rsrqTextView.text = "RSRQ: $rsrq"
            if (rsrqValue != CellInfo.UNAVAILABLE && rsrqValue != Integer.MAX_VALUE) {
                val rsrqColor = getColorForSignal(rsrqValue, -20, -10)
                rsrqTextView.setTextColor(rsrqColor)
            } else {
                rsrqTextView.setTextColor(Color.BLACK)
            }

            sinrTextView.text = "SINR: $sinr"
            if (sinrValue != CellInfo.UNAVAILABLE && sinrValue != Integer.MAX_VALUE) {
                val sinrColor = getColorForSignal(sinrValue, 0, 20)
                sinrTextView.setTextColor(sinrColor)
            } else {
                sinrTextView.setTextColor(Color.BLACK)
            }

            rssiTextView.text = "RSSI: $rssi"
            if (rssiValue != CellInfo.UNAVAILABLE && rssiValue != Integer.MAX_VALUE) {
                val rssiColor = getColorForSignal(rssiValue, -120, -60)
                rssiTextView.setTextColor(rssiColor)
            } else {
                rssiTextView.setTextColor(Color.BLACK)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayNrInfo(
        cellIdentity: CellIdentityNr,
        signalStrength: CellSignalStrengthNr
    ) {
        val nrarfcn = if (cellIdentity.nrarfcn != CellInfo.UNAVAILABLE) cellIdentity.nrarfcn.toString() else "N/A"
        val band = "NR ARFCN: $nrarfcn"

        val pci = if (cellIdentity.pci != CellInfo.UNAVAILABLE) cellIdentity.pci.toString() else "N/A"
        val tac = if (cellIdentity.tac != CellInfo.UNAVAILABLE) cellIdentity.tac.toString() else "N/A"
        val nci = if (cellIdentity.nci != CellInfo.UNAVAILABLE_LONG) cellIdentity.nci.toString() else "N/A"
        val cellsiteIdText = "PCI: $pci, TAC: $tac, NCI: $nci"

        val ssRsrpValue = signalStrength.ssRsrp
        val ssRsrp = if (ssRsrpValue != CellInfo.UNAVAILABLE) "$ssRsrpValue dBm" else "N/A"

        val ssRsrqValue = signalStrength.ssRsrq
        val ssRsrq = if (ssRsrqValue != CellInfo.UNAVAILABLE) "$ssRsrqValue dB" else "N/A"

        val ssSinrValue = signalStrength.ssSinr
        val ssSinr =
            if (ssSinrValue != CellInfo.UNAVAILABLE && ssSinrValue != Integer.MAX_VALUE) "$ssSinrValue dB" else "N/A"

        runOnUiThread {
            bandTextView.text = "Network: 5G NR ($band)"
            cellsiteIdTextView.text = cellsiteIdText

            rsrpTextView.text = "SS-RSRP: $ssRsrp"
            if (ssRsrpValue != CellInfo.UNAVAILABLE && ssRsrpValue != Integer.MAX_VALUE) {
                val rsrpColor = getColorForSignal(ssRsrpValue, -120, -80)
                rsrpTextView.setTextColor(rsrpColor)
            } else {
                rsrpTextView.setTextColor(Color.BLACK)
            }

            rsrqTextView.text = "SS-RSRQ: $ssRsrq"
            if (ssRsrqValue != CellInfo.UNAVAILABLE && ssRsrqValue != Integer.MAX_VALUE) {
                val rsrqColor = getColorForSignal(ssRsrqValue, -20, -10)
                rsrqTextView.setTextColor(rsrqColor)
            } else {
                rsrqTextView.setTextColor(Color.BLACK)
            }

            sinrTextView.text = "SS-SINR: $ssSinr"
            if (ssSinrValue != CellInfo.UNAVAILABLE && ssSinrValue != Integer.MAX_VALUE) {
                val sinrColor = getColorForSignal(ssSinrValue, 0, 20)
                sinrTextView.setTextColor(sinrColor)
            } else {
                sinrTextView.setTextColor(Color.BLACK)
            }

            rssiTextView.text = "RSSI: N/A"
            rssiTextView.setTextColor(Color.BLACK)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayCaInfo(allCellInfo: List<CellInfo>) {
        val lteCells = allCellInfo.filterIsInstance<CellInfoLte>().filter { it.isRegistered }
        val nrCells = allCellInfo.filterIsInstance<CellInfoNr>().filter { it.isRegistered }

        val caConfig = StringBuilder()

        if (nrCells.isNotEmpty()) {
            caConfig.append("5G NR Active")
            if (lteCells.isNotEmpty()) {
                caConfig.append(" (NSA)\n")
            } else {
                caConfig.append(" (SA)\n")
            }

            if (nrCells.size > 1) {
                caConfig.append("NR CA: (${nrCells.size} CC)\n")
            } else {
                caConfig.append("NR: 1CC\n")
            }

            nrCells.forEachIndexed { index, cell ->
                val cellIdentity = cell.cellIdentity as CellIdentityNr
                val signalStrength = cell.cellSignalStrength as CellSignalStrengthNr
                val status = if (index == 0) "PCell" else "SCell"
                val nrarfcn = cellIdentity.nrarfcn
                val ssRsrp = signalStrength.ssRsrp

                caConfig.append("- $status: NR-ARFCN $nrarfcn, SS-RSRP $ssRsrp dBm\n")
            }
        }

        if (lteCells.isNotEmpty()) {
            if (caConfig.isNotEmpty()) {
                caConfig.append("\n")
            }

            if (lteCells.size > 1) {
                caConfig.append("LTE CA: (${lteCells.size} CC)\n")
            } else {
                caConfig.append("LTE: 1CC\n")
            }

            val pCell = lteCells.firstOrNull { it.isRegistered }
            if (pCell != null) {
                val pCellIdentity = pCell.cellIdentity as CellIdentityLte
                val pCellSignalStrength = pCell.cellSignalStrength as CellSignalStrengthLte
                val pCellBand = "LTE Band ${pCellIdentity.bandwidth / 1000}MHz"
                val pCellRsrp = pCellSignalStrength.rsrp

                caConfig.append("- PCell: $pCellBand, RSRP: $pCellRsrp dBm\n")

                val sCells = lteCells.filter { it != pCell && it.isRegistered }
                sCells.forEachIndexed { index, sCell ->
                    val sCellIdentity = sCell.cellIdentity as CellIdentityLte
                    val sCellSignalStrength = sCell.cellSignalStrength as CellSignalStrengthLte
                    val sCellBand = "LTE Band ${sCellIdentity.bandwidth / 1000}MHz"
                    val sCellRsrp = sCellSignalStrength.rsrp
                    caConfig.append("- SCell ${index + 1}: $sCellBand, RSRP: $sCellRsrp dBm\n")
                }
            }
        }

        if (caConfig.isEmpty()) {
            caInfoTextView.text = "CA: N/A"
        } else {
            caInfoTextView.text = caConfig.toString().trim()
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runSpeedTest() {
        downloadSpeedTextView.text = "Download Speed: Testing..."

        activityScope.launch(Dispatchers.IO) {
            try {
                val url = URL(TEST_FILE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text = "Download Speed: Failed (Server Error ${connection.responseCode})"
                    }
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val inputStream = connection.inputStream
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    downloadedBytes += bytesRead
                }
                inputStream.close()
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime).toDouble() / 1000.0
                connection.disconnect()

                if (duration > 0) {
                    val downloadSpeedMbps = (downloadedBytes * 8.0 / (duration * 1000000.0))
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text = "Download Speed: %.2f Mbps".format(downloadSpeedMbps)

                        // FIX: Explicit runtime permission check and qualified 'this' reference
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.READ_PHONE_STATE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.e("SpeedTest", "Permissions revoked, cannot save data.")
                            return@launch
                        }

                        saveCapturedData(downloadSpeedMbps)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        downloadSpeedTextView.text = "Download Speed: Calculation error"
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedTest", "Error during speed test: ${e.message}", e)
                launch(Dispatchers.Main) {
                    downloadSpeedTextView.text = "Download Speed: Failed (${e.javaClass.simpleName})"
                }
            }
        }
    }

    private fun getColorForSignal(value: Int, min: Int, max: Int): Int {
        if (value >= max) {
            return Color.rgb(0, 128, 0)
        }
        if (value <= min) {
            return Color.rgb(255, 0, 0)
        }
        val normalizedValue = (value - min).toFloat() / (max - min)
        val red = (255 * (1 - normalizedValue)).toInt()
        val green = (255 * normalizedValue).toInt()
        return Color.rgb(red, green, 0)
    }

    @OptIn(InternalSerializationApi::class)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCapturedData(downloadSpeedMbps: Double) {
        // FIX: Explicit runtime check is already done in runSpeedTest, but keeping this
        // try-catch block for absolute safety against concurrent permission changes.
        try {
            val allCellInfo = telephonyManager.allCellInfo
            val carriers = captureNetworkData(allCellInfo)

            val session = NetworkSession(
                timestamp = System.currentTimeMillis(),
                networkName = telephonyManager.networkOperatorName,
                downloadSpeedMbps = downloadSpeedMbps,
                carriers = carriers
            )

            val jsonString = Json.encodeToString(session)
            Log.d("JSON_DATA", jsonString)
            saveJsonToInternalStorage(applicationContext, jsonString)
        } catch (e: SecurityException) {
            Log.e("JSON_DATA", "SecurityException during data capture: ${e.message}")
        } catch (e: Exception) {
            Log.e("JSON_DATA", "Error during data capture: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun captureNetworkData(allCellInfo: List<CellInfo>): List<RfAndCaData> {
        val carriers = mutableListOf<RfAndCaData>()

        // LTE: Filter for both registered (PCell) and unregistered (SCells) to get CA info
        val servingLteCells = allCellInfo.filterIsInstance<CellInfoLte>()

        val pCellLte = servingLteCells.firstOrNull { it.isRegistered }

        // 1. Process LTE PCell
        if (pCellLte != null) {
            val identity = pCellLte.cellIdentity as CellIdentityLte
            val signal = pCellLte.cellSignalStrength as CellSignalStrengthLte

            carriers.add(RfAndCaData(
                type = "LTE",
                status = "PCell",
                band = "LTE Band ${identity.bandwidth / 1000}MHz",
                pci = if (identity.pci != CellInfo.UNAVAILABLE) identity.pci else null,
                tac = if (identity.tac != CellInfo.UNAVAILABLE) identity.tac else null,
                ci = if (identity.ci != CellInfo.UNAVAILABLE) identity.ci.toLong() else null,
                rsrp = if (signal.rsrp != CellInfo.UNAVAILABLE) signal.rsrp else null,
                rsrq = if (signal.rsrq != CellInfo.UNAVAILABLE) signal.rsrq else null,
                sinr = if (signal.rssnr != CellInfo.UNAVAILABLE) signal.rssnr else null,
                rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && signal.rssi != CellInfo.UNAVAILABLE) signal.rssi else null
            ))

            // 2. Process LTE SCells (filter out PCell, rely on modem reporting SCells)
            val sCellsLte = servingLteCells.filter { it != pCellLte }
            sCellsLte.forEach { sCell ->
                val sIdentity = sCell.cellIdentity as CellIdentityLte
                val sSignal = sCell.cellSignalStrength as CellSignalStrengthLte
                carriers.add(RfAndCaData(
                    type = "LTE",
                    status = "SCell",
                    band = "LTE Band ${sIdentity.bandwidth / 1000}MHz",
                    pci = if (sIdentity.pci != CellInfo.UNAVAILABLE) sIdentity.pci else null,
                    tac = if (sIdentity.tac != CellInfo.UNAVAILABLE) sIdentity.tac else null,
                    ci = if (sIdentity.ci != CellInfo.UNAVAILABLE) sIdentity.ci.toLong() else null,
                    rsrp = if (sSignal.rsrp != CellInfo.UNAVAILABLE) sSignal.rsrp else null,
                    rsrq = if (sSignal.rsrq != CellInfo.UNAVAILABLE) sSignal.rsrq else null,
                    sinr = if (sSignal.rssnr != CellInfo.UNAVAILABLE) sSignal.rssnr else null,
                    rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sSignal.rssi != CellInfo.UNAVAILABLE) sSignal.rssi else null
                ))
            }
        }

        // NR: Filter for all CellInfoNr to capture CA (PCell/Anchor and SCells)
        val servingNrCells = allCellInfo.filterIsInstance<CellInfoNr>()
        servingNrCells.forEachIndexed { index, cell ->
            val identity = cell.cellIdentity as CellIdentityNr
            val signal = cell.cellSignalStrength as CellSignalStrengthNr

            // Assign status based on registration or index, but note that CA SCells are often not "registered"
            val status = if (cell.isRegistered) "PCell" else "SCell"

            carriers.add(RfAndCaData(
                type = "NR",
                status = status,
                band = "NR-ARFCN ${identity.nrarfcn}",
                pci = if (identity.pci != CellInfo.UNAVAILABLE) identity.pci else null,
                tac = if (identity.tac != CellInfo.UNAVAILABLE) identity.tac else null,
                ci = if (identity.nci != CellInfo.UNAVAILABLE_LONG) identity.nci else null,
                rsrp = if (signal.ssRsrp != CellInfo.UNAVAILABLE) signal.ssRsrp else null,
                rsrq = if (signal.ssRsrq != CellInfo.UNAVAILABLE) signal.ssRsrq else null,
                sinr = if (signal.ssSinr != CellInfo.UNAVAILABLE) signal.ssSinr else null,
                rssi = null
            ))
        }

        val gsmCells = allCellInfo.filterIsInstance<CellInfoGsm>().filter { it.isRegistered }
        gsmCells.forEach { cell ->
            val identity = cell.cellIdentity as CellIdentityGsm
            val signal = cell.cellSignalStrength as CellSignalStrengthGsm
            carriers.add(RfAndCaData(
                type = "GSM",
                status = "PCell",
                band = "GSM ARFCN ${identity.arfcn}",
                pci = if (identity.psc != CellInfo.UNAVAILABLE) identity.psc else null,
                tac = if (identity.lac != CellInfo.UNAVAILABLE) identity.lac else null,
                ci = if (identity.cid != CellInfo.UNAVAILABLE) identity.cid.toLong() else null,
                rsrp = null,
                rsrq = null,
                sinr = null,
                rssi = if (signal.dbm != CellInfo.UNAVAILABLE) signal.dbm else null
            ))
        }

        return carriers
    }

    private fun saveJsonToInternalStorage(context: Context, jsonData: String) {
        try {
            val fileName = "network_data_${System.currentTimeMillis()}.json"
            val fileOutputStream: FileOutputStream = context.openFileOutput(fileName, Context.MODE_PRIVATE)
            fileOutputStream.write(jsonData.toByteArray())
            fileOutputStream.close()
            Log.i("FileSave", "JSON data successfully saved to $fileName")
        } catch (e: Exception) {
            Log.e("FileSave", "Error saving file: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
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