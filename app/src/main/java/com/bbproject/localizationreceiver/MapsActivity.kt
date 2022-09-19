package com.bbproject.localizationreceiver

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.bbproject.localizationreceiver.databinding.ActivityMapsBinding
import com.bbproject.localizationreceiver.service.LocationUpdatesService
import com.bbproject.localizationreceiver.utils.Utils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*


class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SharedPreferences.OnSharedPreferenceChangeListener  {

    companion object {

        private val TAG = MapsActivity::class.java.simpleName

        // Used in checking for runtime permissions.
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 34
        private const val ZINDEX_KIDS = 100001f
        private const val ZINDEX_GPS = 100000f
    }

    // The BroadcastReceiver used to listen from broadcasts from the service.
    private var myReceiver: MyReceiver? = null

    // A reference to the service used to get location updates.
    private var mService: LocationUpdatesService? = null

    // Tracks the bound state of the service.
    private var mBound = false

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding

    private var myRef: DatabaseReference? = null
    private var mCurrentMap: SortedMap<String, LatLng> = sortedMapOf()

    private lateinit var auth: FirebaseAuth

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var eText: TextView
    private lateinit var eRight: Button
    private lateinit var eLeft: Button
    private lateinit var currentDate: String
    private lateinit var myPositionButton: Button
    private lateinit var gps: RelativeLayout

    var picker: DatePickerDialog? = null
    var currentMarker: Marker? = null
    var currentKidsMarker: Marker? = null
//    var seekbar: SeekBar? = null
    var currentCameraPosition = -1
    var maxCameraPosition = -1
    private lateinit var minusButton: Button
    private lateinit var plusButton: Button

    // Monitors the state of the connection to the service.
    private var mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as LocationUpdatesService.LocalBinder
            mService = binder.getService()
            mBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mService = null
            mBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val current = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        currentDate = current.format(formatter)

        eText = findViewById(R.id.date)
        eRight = findViewById(R.id.right)
        eLeft = findViewById(R.id.left)
        gps = findViewById(R.id.gps)
        //seekbar = findViewById(R.id.seekbar)
        minusButton = findViewById(R.id.minus)
        plusButton = findViewById(R.id.plus)

        val cldr: Calendar = Calendar.getInstance()
        val day: Int = cldr.get(Calendar.DAY_OF_MONTH)
        val month: Int = cldr.get(Calendar.MONTH)
        val year: Int = cldr.get(Calendar.YEAR)

        eText.setText("$day/${month + 1}/$year")
        eText.setOnClickListener {
            picker = DatePickerDialog(this,
                { _, year, monthOfYear, dayOfMonth ->
                    var m = (monthOfYear + 1).toString()
                    if (m.length == 1) {
                        m = "0$m"
                    }
                    var d = dayOfMonth.toString()
                    if (d.length == 1) {
                        d = "0$d"
                    }
                    eText.setText("$dayOfMonth/${monthOfYear+1}/$year")
                    if ("$year-$m-$d" != currentDate) {
                        myRef = null
                        mMap.clear()
                        currentMarker = null
                        currentKidsMarker = null
                        currentDate = "$year-$m-$d"
                        mCurrentMap = sortedMapOf()
                        updateUI(auth.currentUser)
                    }
                },
                year,
                month,
                day
            )
            picker?.show()
        }
        eRight.setOnClickListener {
            val date = eText.text.split("/")
            val curDate: LocalDate = LocalDate.of(date[2].toInt(),date[1].toInt(),date[0].toInt()).plusDays(1)
            var m = (curDate.monthValue).toString()
            if (m.length == 1) {
                m = "0$m"
            }
            var d = curDate.dayOfMonth.toString()
            if (d.length == 1) {
                d = "0$d"
            }
            eText.setText("${curDate.dayOfMonth}/${curDate.monthValue}/${curDate.year}")
            if ("${curDate.year}-$m-$d" != currentDate) {
                myRef = null
                mMap.clear()
                currentMarker = null
                currentKidsMarker = null
                currentDate = "${curDate.year}-$m-$d"
                mCurrentMap = sortedMapOf()
                updateUI(auth.currentUser)
            }
        }
        eLeft.setOnClickListener {
            val date = eText.text.split("/")
            val curDate: LocalDate = LocalDate.of(date[2].toInt(),date[1].toInt(),date[0].toInt()).minusDays(1)
            var m = (curDate.monthValue).toString()
            if (m.length == 1) {
                m = "0$m"
            }
            var d = curDate.dayOfMonth.toString()
            if (d.length == 1) {
                d = "0$d"
            }
            eText.setText("${curDate.dayOfMonth}/${curDate.monthValue}/${curDate.year}")
            if ("${curDate.year}-$m-$d" != currentDate) {
                myRef = null
                mMap.clear()
                currentMarker = null
                currentKidsMarker = null
                currentDate = "${curDate.year}-$m-$d"
                mCurrentMap = sortedMapOf()
                updateUI(auth.currentUser)
            }
        }
        plusButton.setOnTouchListener(
            object: View.OnTouchListener{
                override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
                    if (currentCameraPosition < maxCameraPosition) {
                        currentCameraPosition ++
                        var idx = 0
                        run breaking@{
                            mCurrentMap.forEach { pos ->
                                if (idx == currentCameraPosition) {
                                    currentKidsMarker?.position = pos.value
                                    currentKidsMarker?.title = pos.key
                                    currentKidsMarker?.showInfoWindow()
                                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos.value, 17.0f),150,null)
                                    return@breaking
                                }
                                idx++
                            }
                        }
                        return true
                    }
                    return false
                }
            }
        )
        minusButton.setOnTouchListener(
            object: View.OnTouchListener{
                override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
                    if (currentCameraPosition > 0) {
                        currentCameraPosition --
                        var idx = 0
                        run breaking@{
                            mCurrentMap.forEach { pos ->
                                if (idx == currentCameraPosition) {
                                    currentKidsMarker?.position = pos.value
                                    currentKidsMarker?.title = pos.key
                                    currentKidsMarker?.showInfoWindow()
                                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos.value, 17.0f),150,null)
                                    return@breaking
                                }
                                idx++
                            }
                        }
                        return true
                    }
                    return false
                }
            }
        )
/*        seekbar?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    var idx = 0
                    run breaking@{
                        mCurrentMap.forEach { pos ->
                            if (idx == progress) {
                                currentKidsMarker?.position = pos.value
                                currentKidsMarker?.title = pos.key
                                currentKidsMarker?.showInfoWindow()
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos.value, 17.0f),150,null)
                                return@breaking
                            }
                            idx++
                        }
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar) {}
                override fun onStopTrackingTouch(p0: SeekBar) {}
            }
        )*/

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
        auth = Firebase.auth

        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
    }

    override fun onStart() {
        super.onStart()
        myReceiver = MyReceiver()
        Intent(this, LocationUpdatesService::class.java).also { intent ->
            bindService(
                intent,
                mServiceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)

        myPositionButton =
            findViewById<View>(R.id.my_position) as Button
        myPositionButton.setOnClickListener{
            if(mService?.isServiceStarted() == true) {
                mService!!.removeLocationUpdates()
            } else {
                if (!checkPermissions()) {
                    requestPermissions()
                } else {
                    mService!!.requestLocationUpdates()
                }
            }
        }

        updateUI(auth.currentUser)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            myReceiver!!,
            IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(myReceiver!!)
        super.onPause()
    }

    override fun onStop() {
        if (mBound) {
            // Unbind from the service. This signals to the service that this activity is no longer
            // in the foreground, and the service can respond by promoting itself to a foreground
            // service.
            unbindService(mServiceConnection)
            mBound = false
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithCredential:success")
                    val user = auth.currentUser
                    updateUI(user)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    updateUI(null)
                }
            }
    }

    private fun signIn() {
        val startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
                if (result?.resultCode == Activity.RESULT_OK) {
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    try {
                        // Google Sign In was successful, authenticate with Firebase
                        val account = task.getResult(ApiException::class.java)!!
                        Log.d(TAG, "firebaseAuthWithGoogle:" + account.id)
                        firebaseAuthWithGoogle(account.idToken!!)
                    } catch (e: ApiException) {
                        // Google Sign In failed, update UI appropriately
                        Log.w(TAG, "Google sign in failed", e)
                    }
                } else {
                    // Google Sign In failed, update UI appropriately
                    Log.e(TAG, "ResultCode: ${result?.resultCode}")
                }
            }

        startForResult.launch(googleSignInClient.signInIntent)
    }

    private fun updateUI(user: FirebaseUser?) {
        Log.d(TAG, "Firebase user : ${user?.uid}")
        if (user != null) {
            if (myRef == null) {
                FirebaseApp.initializeApp(applicationContext)
                val database = Firebase.database

                myRef = database.getReference("gps").child(currentDate)
                currentCameraPosition = -1
                myRef!!.get().addOnCompleteListener(this){
                    val polyOpt = PolylineOptions()
                        .width(10f)
                        .pattern(listOf(Dot()))
                        .color(Color.RED)

                    val values = it.result.getValue<HashMap<String, HashMap<String, Double>>>()
                    val sortedList = values?.toSortedMap(compareBy { a -> a} )

                    var zindex = 0f
                    val bounds = LatLngBounds.builder()
                    val dot = BitmapFromVector(applicationContext, R.drawable.ic_dot, 64, 64)
                    sortedList?.forEach { point ->
                        val currentPos = LatLng(point.value["y"]!!, point.value["x"]!!)
                        bounds.include(currentPos)
                        polyOpt.add(currentPos)
                        mMap.addMarker(MarkerOptions().position(currentPos)
                            .icon(dot).anchor(0.5f,0.5f).title(point.key).zIndex(zindex))
                        if (sortedList.lastKey() == point.key) {
                            currentKidsMarker = mMap.addMarker(MarkerOptions().position(currentPos).icon(
                                BitmapFromVector(applicationContext, R.drawable.ic_girl2)
                            ).anchor(0.5f,0.5f).title(point.key).zIndex(zindex))
                            currentKidsMarker?.showInfoWindow()
                            //mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 17.0f))
                            mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 20))
                        }

                        mCurrentMap[point.key] = currentPos
                        zindex++
                    }
                    if (zindex==0f) {
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.68, 139.76), 10.0f))
                    }
                    //seekbar?.max = (zindex-1).toInt()
                    //seekbar?.progress = (zindex-1).toInt()
                    currentCameraPosition = (zindex-1).toInt()
                    maxCameraPosition = (zindex-1).toInt()
                    mMap.addPolyline(polyOpt)

                    val current = LocalDateTime.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    val today = current.format(formatter)
                    if (currentDate == today) {

                        myRef!!.addChildEventListener(object : ChildEventListener {
                            override fun onChildAdded(
                                dataSnapshot: DataSnapshot,
                                previousChildName: String?
                            ) {
                                Log.d(TAG, "Value is: ${dataSnapshot.value}")

                                val value = dataSnapshot.getValue<HashMap<String, Double>>()

                                if (!mCurrentMap.containsKey(dataSnapshot.key)&& value!!["y"] != null && value["x"] != null) {
                                    val currentPos = LatLng(value["y"]!!, value["x"]!!)
                                    mMap.addMarker(MarkerOptions().position(currentPos).title(dataSnapshot.key))
                                    mCurrentMap[dataSnapshot.key] = currentPos
                                    if (currentKidsMarker != null) {
                                        currentKidsMarker?.position = currentPos
                                        currentKidsMarker?.title = dataSnapshot.key
                                    } else {
                                        currentKidsMarker = mMap.addMarker(
                                            MarkerOptions().position(currentPos).icon(
                                                BitmapFromVector(
                                                    applicationContext,
                                                    R.drawable.ic_girl2
                                                )
                                            ).anchor(0.5f, 0.5f).title(dataSnapshot.key).zIndex(ZINDEX_KIDS)
                                        )
                                    }
                                    if (currentCameraPosition == maxCameraPosition) {
                                        currentCameraPosition++

                                        //seekbar?.max = (seekbar?.max?:0) + 1
                                        //seekbar?.progress = (seekbar?.progress?:0) + 1
                                    }/* else {
                                        seekbar?.max = (seekbar?.max?:0) + 1
                                    }*/
                                    currentKidsMarker?.showInfoWindow()
                                    maxCameraPosition++
                                }
                            }

                            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}

                            override fun onChildRemoved(snapshot: DataSnapshot) {}

                            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                            override fun onCancelled(error: DatabaseError) {
                                Log.w(TAG, "Failed to read value.", error.toException())
                            }
                        })
                    }
                }.addOnFailureListener {
                    Log.d("TEST","TEST")
                }

            }
        } else {
            signIn()
        }
    }

    /**
     * Returns the current state of the permissions needed.
     */
    private fun checkPermissions(): Boolean {
        return PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun requestPermissions() {
        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        // Provide an additional rationale to the user. This would happen if the user denied the
        // request previously, but didn't check the "Don't ask again" checkbox.
        if (shouldProvideRationale) {
            Log.i(TAG, "Displaying permission rationale to provide additional context.")
            Snackbar.make(
                findViewById(R.id.map),
                R.string.permission_rationale,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.ok) { // Request permission
                    ActivityCompat.requestPermissions(
                        this@MapsActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_PERMISSIONS_REQUEST_CODE
                    )
                }
                .show()
        } else {
            Log.i(TAG, "Requesting permission")
            // Request permission. It's possible this can be auto answered if device policy
            // sets the permission in a given state or the user denied the permission
            // previously and checked "Never ask again".
            ActivityCompat.requestPermissions(
                this@MapsActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    /**
     * Callback received when a permissions request has been completed.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        Log.i(TAG, "onRequestPermissionResult")
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isEmpty()) {
                // If user interaction was interrupted, the permission request is cancelled and you
                // receive empty arrays.
                Log.i(TAG, "User interaction was cancelled.")
            } else if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted.
                mService!!.requestLocationUpdates()
            } else {
                // Permission denied.
                setButtonsState(false)
                Snackbar.make(
                    findViewById(R.id.map),
                    R.string.permission_denied_explanation,
                    Snackbar.LENGTH_INDEFINITE
                )
                    .setAction(R.string.settings) { // Build intent that displays the App settings screen.
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri: Uri = Uri.fromParts(
                            "package",
                            BuildConfig.APPLICATION_ID, null
                        )
                        intent.data = uri
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }
                    .show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * Receiver for broadcasts sent by [LocationUpdatesService].
     */
    inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val location: Location? =
                intent.getParcelableExtra(LocationUpdatesService.EXTRA_LOCATION)
            if (location != null) {
                val currentPos = LatLng(location.latitude, location.longitude)

                if (currentMarker == null) {
                    currentMarker = mMap.addMarker(MarkerOptions().position(currentPos).icon(
                        BitmapFromVector(applicationContext, R.drawable.ic_avatar)
                    ).zIndex(ZINDEX_GPS))

                    val bounds = LatLngBounds.builder()
                    bounds.include(currentMarker!!.position)
                    if (currentKidsMarker!=null) {
                        bounds.include(currentKidsMarker!!.position)
                        mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 20))
                    } else {
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 17.0f))
                    }

                } else {
                    currentMarker?.position = currentPos
                }

                Toast.makeText(
                    this@MapsActivity, Utils.getLocationText(location),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun BitmapFromVector(context: Context, vectorResId: Int, width: Int=96, height: Int=96): BitmapDescriptor? {
        // below line is use to generate a drawable.
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)

        // below line is use to set bounds to our vector drawable.
        vectorDrawable!!.setBounds(
            0,
            0,
            width,
            height
        )

        // below line is use to create a bitmap for our
        // drawable which we have added.
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        // below line is use to add bitmap in our canvas.
        val canvas = Canvas(bitmap)

        // below line is use to draw our
        // vector drawable in canvas.
        vectorDrawable.draw(canvas)

        // after generating our bitmap we are returning our bitmap.
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, s: String) {
        // Update the buttons state depending on whether location updates are being requested.
        if (s == Utils.KEY_REQUESTING_LOCATION_UPDATES) {
            setButtonsState(
                sharedPreferences.getBoolean(
                    Utils.KEY_REQUESTING_LOCATION_UPDATES,
                    false
                )
            )
        }
    }

    private fun setButtonsState(requestingLocationUpdates: Boolean) {
        if (requestingLocationUpdates) {
            gps.setBackgroundResource(R.drawable.red_rounded_button)
//            mRequestLocationUpdatesButton?.isEnabled = false
//            mRemoveLocationUpdatesButton?.isEnabled = true
        } else {
            gps.setBackgroundResource(R.drawable.rounded_button)
//            mRequestLocationUpdatesButton?.isEnabled = true
//            mRemoveLocationUpdatesButton?.isEnabled = false
        }
    }
}