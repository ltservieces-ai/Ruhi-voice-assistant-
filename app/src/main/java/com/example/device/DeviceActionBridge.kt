package com.example.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

data class ContactInfo(
    val id: String,
    val name: String,
    val phoneNumber: String
)

sealed class ActionResult {
    data class Success(val message: String, val details: Map<String, Any?> = emptyMap()) : ActionResult()
    data class Failure(val error: String, val reason: String = "") : ActionResult()
    data class MultipleMatches(val message: String, val contacts: List<ContactInfo>) : ActionResult()
}

class DeviceActionBridge(private val context: Context) {

    fun openWhatsApp(): ActionResult {
        return try {
            val pm = context.packageManager
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            var launchIntent: Intent? = null
            for (pkg in packages) {
                launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) break
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ActionResult.Success("WhatsApp opened successfully.")
            } else {
                // Fallback to web link if supported or report not installed
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (webIntent.resolveActivity(pm) != null) {
                    context.startActivity(webIntent)
                    ActionResult.Success("Opened WhatsApp web gateway.")
                } else {
                    ActionResult.Failure("WhatsApp is not installed on this device.", "app_not_installed")
                }
            }
        } catch (e: Exception) {
            ActionResult.Failure("Failed to open WhatsApp: ${e.localizedMessage}")
        }
    }

    fun openApp(appName: String): ActionResult {
        val trimmed = appName.trim().lowercase()
        return try {
            val pm = context.packageManager
            
            // First check common special system intents
            when {
                trimmed.contains("setting") -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return ActionResult.Success("Opened device Settings.")
                }
                trimmed.contains("camera") -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(pm) != null) {
                        context.startActivity(intent)
                        return ActionResult.Success("Opened Camera.")
                    }
                }
                trimmed.contains("whatsapp") -> {
                    return openWhatsApp()
                }
            }

            // Known app package mapping
            val knownPackages = mapOf(
                "youtube" to "com.google.android.youtube",
                "instagram" to "com.instagram.android",
                "chrome" to "com.android.chrome",
                "maps" to "com.google.android.apps.maps",
                "google maps" to "com.google.android.apps.maps",
                "spotify" to "com.spotify.music",
                "gmail" to "com.google.android.gm",
                "play store" to "com.android.vending",
                "telegram" to "org.telegram.messenger",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "clock" to "com.google.android.deskclock",
                "calculator" to "com.google.android.calculator"
            )

            for ((key, pkg) in knownPackages) {
                if (trimmed.contains(key)) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionResult.Success("Opened $key successfully.")
                    }
                }
            }

            // Search installed applications by label
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedApps) {
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                if (label.contains(trimmed) || trimmed.contains(label)) {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionResult.Success("Opened ${pm.getApplicationLabel(appInfo)} successfully.")
                    }
                }
            }

            // If not found, fallback to browser search or report not installed
            ActionResult.Failure("Application '$appName' is not installed on this device.", "app_not_found")
        } catch (e: Exception) {
            ActionResult.Failure("Could not open $appName: ${e.localizedMessage}")
        }
    }

    fun openUrl(rawUrl: String): ActionResult {
        return try {
            val url = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                "https://$rawUrl"
            } else {
                rawUrl
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opened URL: $url")
        } catch (e: Exception) {
            ActionResult.Failure("Failed to open URL $rawUrl: ${e.localizedMessage}")
        }
    }

    fun makeCall(phoneNumber: String): ActionResult {
        val sanitized = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (sanitized.isEmpty()) {
            return ActionResult.Failure("Invalid phone number provided.", "invalid_number")
        }

        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            // Use ACTION_CALL if direct call permission is granted, otherwise use ACTION_DIAL for safe pre-filled dialer
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)
            ActionResult.Success("Initiating call to $sanitized", mapOf("phoneNumber" to sanitized, "directCall" to hasCallPermission))
        } catch (e: Exception) {
            ActionResult.Failure("Failed to place call: ${e.localizedMessage}")
        }
    }

    fun searchContacts(query: String): List<ContactInfo> {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return emptyList()
        }

        val contactsList = mutableListOf<ContactInfo>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val searchTerms = expandRelationshipTerms(query.trim())
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        
        try {
            for (term in searchTerms) {
                val selectionArgs = arrayOf("%$term%")
                context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (cursor.moveToNext()) {
                        val id = if (idCol >= 0) cursor.getString(idCol) else ""
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else ""
                        val number = if (numCol >= 0) cursor.getString(numCol) else ""

                        if (name.isNotBlank() && number.isNotBlank()) {
                            val info = ContactInfo(id, name, number)
                            if (contactsList.none { it.name.equals(name, ignoreCase = true) && it.phoneNumber == number }) {
                                contactsList.add(info)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log or ignore query exception
        }

        return contactsList
    }

    fun callContact(contactName: String): ActionResult {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return ActionResult.Failure(
                "Contacts permission is required to search contacts. Please grant Contacts permission in the app.",
                "permission_denied"
            )
        }

        val matches = searchContacts(contactName)
        return when {
            matches.isEmpty() -> {
                ActionResult.Failure("No contact found matching '$contactName'.", "not_found")
            }
            matches.size == 1 -> {
                val target = matches.first()
                val callResult = makeCall(target.phoneNumber)
                if (callResult is ActionResult.Success) {
                    ActionResult.Success(
                        "Calling ${target.name} (${target.phoneNumber}).",
                        mapOf("name" to target.name, "phoneNumber" to target.phoneNumber)
                    )
                } else {
                    callResult
                }
            }
            else -> {
                // Group by distinct name
                val distinctNames = matches.groupBy { it.name }
                if (distinctNames.size == 1) {
                    // Same contact name, maybe multiple numbers
                    val target = matches.first()
                    makeCall(target.phoneNumber)
                    ActionResult.Success(
                        "Calling ${target.name} (${target.phoneNumber}).",
                        mapOf("name" to target.name, "phoneNumber" to target.phoneNumber)
                    )
                } else {
                    val namesList = matches.map { "${it.name} (${it.phoneNumber})" }
                    ActionResult.MultipleMatches(
                        "Found ${matches.size} contacts matching '$contactName': ${namesList.joinToString(", ")}. Which one should I call?",
                        matches
                    )
                }
            }
        }
    }

    private fun expandRelationshipTerms(query: String): List<String> {
        val lower = query.lowercase()
        return when {
            lower in listOf("mom", "mummy", "mother", "maa", "mataji", "ammi") ->
                listOf("Mom", "Mummy", "Mother", "Maa", "Ammi", query)
            lower in listOf("dad", "daddy", "father", "papa", "pitaji", "abbu") ->
                listOf("Dad", "Daddy", "Father", "Papa", "Abbu", query)
            lower in listOf("brother", "bhai", "bro", "bhaiya") ->
                listOf("Bhai", "Bhaiya", "Brother", "Bro", query)
            lower in listOf("sister", "didi", "sis", "behen") ->
                listOf("Didi", "Sister", "Sis", "Behen", query)
            else -> listOf(query)
        }
    }
}
