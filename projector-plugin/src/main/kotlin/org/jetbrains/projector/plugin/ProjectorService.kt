/*
 * Copyright (c) 2019-2022, JetBrains s.r.o. and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation. JetBrains designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact JetBrains, Na Hrebenech II 1718/10, Prague, 14000, Czech Republic
 * if you need additional information or have any questions.
 */

package org.jetbrains.projector.plugin

import com.intellij.diagnostic.VMOptions
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.projector.agent.AgentLauncher
import org.jetbrains.projector.server.ProjectorServer
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

enum class EnabledState {

  NO_VM_OPTIONS_AND_DISABLED,
  HAS_VM_OPTIONS_AND_DISABLED,
  HAS_VM_OPTIONS_AND_ENABLED,
  STOPPED,
}

class ProjectorConfig : PersistentStateComponent<ProjectorConfig> {
  var host: String? = null
  var port: String? = null
  var confirmConnection: Boolean? = null
  var autostart: Boolean? = null

  private var rwToken: String? = null
  private var roToken: String? = null

  fun storeRWToken(token: String?) {
    if (token != rwToken) {
      rwToken = token
      storeToken(PROJECTOR_RW_TOKEN_KEY, rwToken)
    }
  }

  fun obtainRWToken() = rwToken

  fun storeROToken(token: String?) {
    if (token != roToken) {
      roToken = token
      storeToken(PROJECTOR_RO_TOKEN_KEY, roToken)
    }
  }

  fun obtainROToken() = roToken

  init {
    roToken = loadToken(PROJECTOR_RO_TOKEN_KEY)
    rwToken = loadToken(PROJECTOR_RW_TOKEN_KEY)
  }

  override fun getState(): ProjectorConfig {
    return this
  }

  override fun loadState(state: ProjectorConfig) {
    host = state.host
    port = state.port
    confirmConnection = state.confirmConnection
    autostart = state.autostart
  }

  companion object {
    init {
      migrateTokensToSecureStorage()
    }

    const val STORAGE_NAME = "ProjectorConfig.xml"
  }
}

interface ProjectorStateListener {
  fun stateChanged()
}

@State(name = "Projector", storages = [Storage(ProjectorConfig.STORAGE_NAME)])
class ProjectorService : PersistentStateComponent<ProjectorConfig> {
  private var config: ProjectorConfig = ProjectorConfig()
  private val logger = Logger.getInstance(ProjectorService::class.java)
  private val listeners = arrayListOf<ProjectorStateListener>()

  private var currentSession: Session? = null
    set(value) {
      field = value
      config.host = value?.host
      config.port = value?.port
      config.confirmConnection = value?.confirmConnection
      config.storeRWToken(value?.rwToken)
      config.storeROToken(value?.roToken)
      config.autostart = value?.autostart
    }

  private var enabled: EnabledState = when (areRequiredVmOptionsPresented()) {
    true -> EnabledState.HAS_VM_OPTIONS_AND_DISABLED
    false -> EnabledState.NO_VM_OPTIONS_AND_DISABLED
  }

  fun subscribe(l: ProjectorStateListener) {
    listeners.add(l)
  }

  fun unsubscribe(l: ProjectorStateListener) {
    listeners.remove(l)
  }

  private fun stateChanged() {
    listeners.forEach { it.stateChanged() }
  }

  fun activate() {
    if (confirmRestart(
        "Before enabling Projector for the first time, some run arguments (VM properties) should be set. Can I set them and restart the IDE now?")) {
      getVMOptions()?.let { (content, writeFile) ->
        content
          .lineSequence()
          .filterNot { it.startsWith("-Dswing.bufferPerWindow") || it.startsWith("-Djdk.attach.allowAttachSelf") }
          .plus("-Dswing.bufferPerWindow=false")
          .plus("-Djdk.attach.allowAttachSelf=true")
          .joinToString(separator = System.lineSeparator())
          .let { FileUtil.writeToFile(writeFile, it) }

        stateChanged()
        restartIde()
      } ?: SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(
          null,
          "Can't change VM options. Please see logs to understand the error",
          "Can't set up...",
          JOptionPane.ERROR_MESSAGE,
        )
      }
    }
  }

  private fun disable() {
    AgentLauncher.stopServer(0)
    enabled = EnabledState.STOPPED
    stateChanged()
  }

  private fun enable() {
    if (enabled == EnabledState.HAS_VM_OPTIONS_AND_DISABLED) {
      attachDynamicAgent()
    }
    else {
      check(enabled == EnabledState.STOPPED) { "Bad state: $enabled" }
      AgentLauncher.startServer()
    }
    enabled = EnabledState.HAS_VM_OPTIONS_AND_ENABLED
    stateChanged()
  }

  private fun getVMOptions(): Pair<String, File>? {
    fun getVMOptionsWriteFile(): File? = try { // for 2020.3 and later
      val path: Path? = VMOptions.getWriteFile()
      path?.toUri()?.let(::File)
    }
    catch (e: NoSuchMethodError) { // for < 2020.3
      val writeFileMethod = VMOptions::class.java.getMethod("getWriteFile")
      writeFileMethod.invoke(null) as File?
    }

    val writeFile = getVMOptionsWriteFile()
    if (writeFile == null) {
      logger.warn("VM options file not configured")
      return null
    }

    val templateFile = VMOptions.read()
    if (templateFile == null) {
      logger.warn("VM options file not configured")
      return null
    }

    val s = if (writeFile.exists()) {
      writeFile.readText()
    }
    else {
      templateFile
    }

    return Pair(s, writeFile)
  }

  companion object {
    private val instance: ProjectorService by lazy { service() }

    fun subscribe(l: ProjectorStateListener) = instance.subscribe(l)

    fun unsubscribe(l: ProjectorStateListener) = instance.unsubscribe(l)

    fun enable(session: Session?) {
      if (session != null)
        currentSession = session

      instance.enable()
    }

    fun disable() = instance.disable()
    fun activate() = instance.activate()
    fun getClientList(): Array<Array<String?>> = AgentLauncher.getClientList()
    fun disconnectAll() = AgentLauncher.disconnectAll()
    fun disconnectByIp(ip: String) = AgentLauncher.disconnectByIp(ip)
    fun addObserver(listener: PropertyChangeListener) = AgentLauncher.addObserver(listener)
    fun removeObserver(listener: PropertyChangeListener) = AgentLauncher.removeObserver(listener)
    fun autostartIfRequired() {
      if (!isHeadlessProjectorDetected() && !isProjectorRunning()) {
        with(ProjectorService) {
          host?.let { host ->
            port?.let { port ->
              if (autostart) {
                val session = Session(host, port, rwToken, roToken, confirmConnection, autostart)
                enable(session)
              }
            }
          }
        }
      }
    }

    var enabled: EnabledState
      get() = instance.enabled
      set(value) {
        instance.enabled = value
      }

    var host: String?
      get() = instance.config.host
      set(value) {
        setSystemProperty(ProjectorServer.HOST_PROPERTY_NAME, value)
        instance.config.host = value
      }

    var port: String?
      get() = instance.config.port
      set(value) {
        setSystemProperty(ProjectorServer.PORT_PROPERTY_NAME, value)
        instance.config.port = value
      }

    var confirmConnection: Boolean
      get() = instance.config.confirmConnection ?: true
      set(value) {
        setSystemProperty(ProjectorServer.ENABLE_CONNECTION_CONFIRMATION, if (value) "true" else "false")
        instance.config.confirmConnection = value
      }

    var rwToken: String?
      get() = instance.config.obtainRWToken()
      set(value) {
        setSystemProperty(ProjectorServer.TOKEN_ENV_NAME, if (value.isNullOrEmpty()) null else value)
        instance.config.storeRWToken(value)
      }

    var roToken: String?
      get() = instance.config.obtainROToken()
      set(value) {
        setSystemProperty(ProjectorServer.RO_TOKEN_ENV_NAME, if (value.isNullOrEmpty()) null else value)
        instance.config.storeROToken(value)
      }

    var autostart: Boolean
      get() = instance.config.autostart ?: false
      set(value) {
        instance.config.autostart = value
      }

    val isSessionRunning: Boolean get() = instance.currentSession != null

    var currentSession: Session
      get() {
        check(isSessionRunning) { "Current session is not available - no active sessions" }
        return instance.currentSession!!
      }
      set(value) {
        instance.currentSession = value
      }
  }

  override fun getState(): ProjectorConfig {
    return config
  }

  override fun loadState(state: ProjectorConfig) {
    config = state
  }
}
