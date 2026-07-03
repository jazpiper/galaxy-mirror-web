package com.example.galaxymirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NetworkTransportDetectorTest {

    @Test
    fun returnsOtherWhenConnectivityManagerIsNull() {
        val context = mock<Context>()
        val appContext = mock<Context>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(null)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.OTHER, detector.currentTransport())
    }

    @Test
    fun returnsOtherWhenActiveNetworkIsNull() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(null)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.OTHER, detector.currentTransport())
    }

    @Test
    fun returnsOtherWhenNetworkCapabilitiesIsNull() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(null)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.OTHER, detector.currentTransport())
    }

    @Test
    fun returnsWifiWhenTransportIsWifi() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val networkCapabilities = mock<NetworkCapabilities>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.WIFI, detector.currentTransport())
    }

    @Test
    fun returnsWifiWhenTransportIsEthernet() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val networkCapabilities = mock<NetworkCapabilities>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(false)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(true)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.WIFI, detector.currentTransport())
    }

    @Test
    fun returnsCellularWhenTransportIsCellular() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val networkCapabilities = mock<NetworkCapabilities>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(false)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(false)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.CELLULAR, detector.currentTransport())
    }

    @Test
    fun returnsOtherWhenTransportIsUnknown() {
        val context = mock<Context>()
        val appContext = mock<Context>()
        val connectivityManager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val networkCapabilities = mock<NetworkCapabilities>()

        whenever(context.applicationContext).thenReturn(appContext)
        whenever(appContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(false)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(false)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(false)

        val detector = NetworkTransportDetector(context)
        assertEquals(StreamNetworkTransport.OTHER, detector.currentTransport())
    }
}
