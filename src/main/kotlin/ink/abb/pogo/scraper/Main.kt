/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper

import POGOProtos.Enums.TutorialStateOuterClass
import POGOProtos.Networking.Requests.Messages.GetPlayerMessageOuterClass
import POGOProtos.Networking.Requests.Messages.MarkTutorialCompleteMessageOuterClass
import POGOProtos.Networking.Requests.RequestTypeOuterClass
import com.pokegoapi.api.PokemonGo
import com.pokegoapi.auth.CredentialProvider
import com.pokegoapi.auth.GoogleAutoCredentialProvider
import com.pokegoapi.auth.GoogleUserCredentialProvider
import com.pokegoapi.auth.PtcCredentialProvider
import com.pokegoapi.exceptions.LoginFailedException
import com.pokegoapi.exceptions.RemoteServerException
import com.pokegoapi.main.ServerRequest
import com.pokegoapi.util.SystemTimeImpl
import ink.abb.pogo.scraper.services.BotService
import ink.abb.pogo.scraper.util.Log
import okhttp3.OkHttpClient
import org.springframework.boot.SpringApplication
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.*

val time = SystemTimeImpl()

fun getAuth(settings: Settings, http: OkHttpClient, writeToken: (String) -> Unit): CredentialProvider {
    val credentials = settings.credentials
    val auth = if (credentials is GoogleCredentials) {
        if (credentials.token.isBlank()) {
            val provider = GoogleUserCredentialProvider(http, time)

            println("Please go to " + GoogleUserCredentialProvider.LOGIN_URL)
            println("Enter authorisation code:")

            val access = readLine()

            // we should be able to login with this token
            provider.login(access)
            println("Refresh token:" + provider.refreshToken)
            Log.normal("Setting Google refresh token in your config")
            credentials.token = provider.refreshToken
            writeToken(credentials.token)

            provider
        } else {
            GoogleUserCredentialProvider(http, credentials.token, time)
        }
    } else if (credentials is GoogleAutoCredentials) {
        GoogleAutoCredentialProvider(http, credentials.username, credentials.password, time)
    } else if (credentials is PtcCredentials) {
        try {
            PtcCredentialProvider(http, credentials.username, credentials.password, time)
        } catch (e: LoginFailedException) {
            throw e
        } catch (e: RemoteServerException) {
            throw e
        } catch (e: Exception) {
            // sometimes throws ArrayIndexOutOfBoundsException or other RTE's
            throw RemoteServerException(e)
        }
    } else {
        throw IllegalStateException("Unknown credentials: ${credentials.javaClass}")
    }

    return auth
}

fun main(args: Array<String>) {
    SpringApplication.run(PokemonGoBotApplication::class.java, *args)
}

fun loadProperties(filename: String): Properties {
    val properties = Properties()
    FileInputStream(filename).use {
        properties.load(it)
    }
    return properties
}

fun startDefaultBot(http: OkHttpClient, service: BotService) {
    var properties: Properties

    var filename = "config.properties"

    try {
        properties = loadProperties(filename)
    } catch (e: FileNotFoundException) {
        Log.red("${filename} file not found. Trying config.properties.txt...")
        try {
            // Fix for Windows users...
            filename += ".txt"
            properties = loadProperties(filename)
        } catch (e: FileNotFoundException) {
            Log.red("${filename} not found as well. Exiting.")
            System.exit(1);
            return
        }
    }

    val settings = SettingsParser(properties).createSettingsFromProperties()
    service.addBot(startBot(settings, http, {
        settings.writeProperty(filename, "token", it)
    }))
}

fun startBot(settings: Settings, http: OkHttpClient, writeToken: (String) -> Unit = {}): Bot {
    Log.normal("Logging in to game server...")

    val retryCount = 3
    val errorTimeout = 1000L

    var retries = retryCount

    var auth: CredentialProvider? = null
    do {
        try {
            auth = getAuth(settings, http, writeToken)
        } catch (e: LoginFailedException) {
            throw IllegalStateException("Server refused your login credentials. Are they correct?")
        } catch (e: RemoteServerException) {
            Log.red("Server returned unexpected error: ${e.message}")
            if (retries-- > 0) {
                Log.normal("Retrying...")
                Thread.sleep(errorTimeout)
            }
        }
    } while (auth == null && retries >= 0)

    retries = retryCount

    var api: PokemonGo? = null
    do {
        try {
            api = PokemonGo(auth, http, time)
        } catch (e: LoginFailedException) {
            throw IllegalStateException("Server refused your login credentials. Are they correct?")
        } catch (e: RemoteServerException) {
            Log.red("Server returned unexpected error: ${e.message}")
            if (retries-- > 0) {
                Log.normal("Retrying...")
                Thread.sleep(errorTimeout)
            }
        }
    } while (api == null && retries >= 0)

    if (api == null) {
        throw IllegalStateException("Failed to login. Stopping")
    }

    Log.normal("Logged in successfully")

    print("Getting profile data from pogo server")
    while (api.playerProfile == null) {
        print(".")
        Thread.sleep(1000)
    }
    println(".")

    if (api.playerProfile.stats == null) {
        // apparently the account didn't except the ToS yet
        val getPlayerMessageBuilder = GetPlayerMessageOuterClass.GetPlayerMessage.newBuilder()

        val tosBuilder = MarkTutorialCompleteMessageOuterClass.MarkTutorialCompleteMessage.newBuilder()
                .addTutorialsCompleted(TutorialStateOuterClass.TutorialState.LEGAL_SCREEN)
                .setSendMarketingEmails(false)
                .setSendPushNotifications(false)

        val serverRequestsPlayer = ServerRequest(RequestTypeOuterClass.RequestType.GET_PLAYER, getPlayerMessageBuilder.build())
        val serverRequestsTutorial = ServerRequest(RequestTypeOuterClass.RequestType.MARK_TUTORIAL_COMPLETE, tosBuilder.build())

        api.getRequestHandler().sendServerRequests(serverRequestsPlayer, serverRequestsTutorial)
        // set stats
        api.inventories.updateInventories(true)
    }

    val bot = Bot(api, settings)

    bot.start()

    return bot
}
