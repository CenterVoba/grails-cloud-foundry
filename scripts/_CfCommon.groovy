/* Copyright 2011 SpringSource.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author Burt Beckwith
 */

import grails.util.GrailsNameUtils
import grails.util.GrailsUtil

import java.text.SimpleDateFormat

import org.apache.log4j.Logger
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException

import com.vmware.appcloud.client.CloudApplication
import com.vmware.appcloud.client.CloudFoundryClient
import com.vmware.appcloud.client.CloudFoundryException
import com.vmware.appcloud.client.CloudInfo
import com.vmware.appcloud.client.CloudService
import com.vmware.appcloud.client.ServiceConfiguration
import com.vmware.appcloud.client.CloudApplication.AppState

includeTargets << grailsScript('_GrailsBootstrap')

target(cfInit: 'General initialization') {
	depends compile, fixClasspath, loadConfig, configureProxy

	try {
		GrailsHttpRequestFactory = classLoader.loadClass('grails.plugin.cloudfoundry.GrailsHttpRequestFactory')

		cfConfig = config.grails.plugin.cloudfoundry

		username = grailsSettings.config.grails.plugin.cloudfoundry.username ?: cfConfig.username
		password = grailsSettings.config.grails.plugin.cloudfoundry.password ?: cfConfig.password

		if (!username || !password) {
			errorAndDie 'grails.plugin.cloudfoundry.username and grails.plugin.cloudfoundry.password must be set in Config.groovy or in .grails/settings.groovy'
		}

		log = Logger.getLogger('grails.plugin.cloudfoundry.Scripts')

		cfTarget = cfConfig.target ?: 'api.cloudfoundry.com'
		cloudControllerUrl = cfTarget.startsWith('http') ? cfTarget : 'http://' + cfTarget

		createClient username, password, cloudControllerUrl

		hyphenatedScriptName = GrailsNameUtils.getScriptName(scriptName)

		argsList = argsMap.params

		CRASH_LOG_NAMES = ['logs/err.log', 'logs/stderr.log', 'logs/stdout.log', 'logs/startup.log']

		isPush = false
	}
	catch (IllegalArgumentException e) {
		System.exit 1
	}
	catch (e) {
		printStackTrace e
		throw e
	}
}

target(fixClasspath: 'Ensures that the classes directories are on the classpath so Config class is found') {
	rootLoader.addURL grailsSettings.classesDir.toURI().toURL()
	rootLoader.addURL grailsSettings.pluginClassesDir.toURI().toURL()
}

target(loadConfig: 'Ensures that the config is properly loaded') {
	binding.variables.remove 'config'
	createConfig()
}

printStackTrace = { e ->
	GrailsUtil.deepSanitize e

	List<StackTraceElement> newTrace = []
	for (StackTraceElement element : e.stackTrace) {
		if (element.fileName && element.lineNumber > 0 && !element.className.startsWith('gant.')) {
			newTrace << element
		}
	}

	if (newTrace) {
		e.stackTrace = newTrace as StackTraceElement[]
	}
	
	e.printStackTrace()
}

doWithTryCatch = { Closure c ->

	try {
		client.loginIfNeeded()
	}
	catch (CloudFoundryException e) {
		println "\nError logging in; please check your username and password\n"
		return
	}

	try {
		c()
	}
	catch (IllegalArgumentException e) {
		// do nothing, usage will be displayed but don't want to System.exit
		// in case we're in interactive
	}
	catch (CloudFoundryException e) {
		println "\nError: $e.message\n"
		printStackTrace e
	}
	catch (HttpServerErrorException e) {
		println "\nError: $e.message\n"
		printStackTrace e
	}
	catch (e) {
		if (e instanceof ResourceAccessException && e.cause instanceof IOException) {
			if (e.cause instanceof ConnectException) {
				println "\nError: Unable to connect to API server - check that grails.plugin.cloudfoundry.target is set correctly and that the server is available\n"
			}
			else if (e.cause instanceof EOFException) {
				println "\nError: EOFException - check that grails.plugin.cloudfoundry.target is set correctly and that the server is available\n"
			}
			else {
				println "\nError: $e.message\n"
			}
		}
		else {
			println "\nError: $e.message\n"
		}
		if (cfConfig.showStackTrace) {
			printStackTrace e
		}
	}
}

errorAndDie = { String message ->
	event('StatusError', [message])
	throw new IllegalArgumentException()
}

getRequiredArg = { int index = 0 ->
	String value = argsList[index]
	if (value) {
		return value
	}
	println "\nUsage (optionals in square brackets):\n$USAGE"
	throw new IllegalArgumentException()
}

prettySize = { long size, int precision = 1 ->
	if (size < 1024) {
		return "${size}B"
	}
	if (size < 1024*1024) {
		return String.format("%.${precision}fK", size / 1024.0)
	}
	if (size < 1024*1024*1024) {
		return String.format("%.${precision}fM", size/ (1024.0 * 1024.0))
	}
	return String.format("%.${precision}fG", size/ (1024.0 * 1024.0 * 1024.0))
}

ask = { String question, String answers = null, String defaultIfMissing = null ->
	String propName = 'cf.ask.' + System.currentTimeMillis()

	def args = [addProperty: propName, message: question]
	if (answers) {
		args.validargs = answers
		if (defaultIfMissing) {
			args.defaultvalue = defaultIfMissing
		}
	}

	ant.input args
	ant.antProject.properties[propName] ?: defaultIfMissing
}

getApplication = { String name = getAppName(), boolean nullIfMissing = false ->
	try {
		return client.getApplication(name)
	}
	catch (CloudFoundryException e) {
		if (e.statusCode == HttpStatus.NOT_FOUND) {
			if (nullIfMissing) {
				return null
			}
			errorAndDie "Application '$name' does not exist."
		}
	}
}

getFile = { int instanceIndex, String path ->
	try {
		client.getFile getAppName(), instanceIndex, path
	}
	catch (ResourceAccessException e) {
		if (!(e.cause instanceof IOException)) {
			throw e
		}
		''
	}
}

formatDate = { Date date, String format = 'MM/dd/yyyy hh:mma' ->
	new SimpleDateFormat(format).format(date)
}

displayLog = { String logPath, int instanceIndex, boolean showError, String destination = null ->
	try {
		String content = getFile(instanceIndex, logPath)
		if (content) {
			if (destination) {
				new File(destination).withWriter { it.write content }
				println "\nWrote $logPath to $destination\n"
			}
			else {
				println "==== $logPath ====\n"
				println content
				println ''
			}
		}
	}
	catch (e) {
		if (showError) {
			println "\nERROR: There was an error retrieving $logPath, please try again"
		}
	}
}

describeHealth = { CloudApplication application ->

	if (!application || !application.state) return 'N/A'

	float runningInstances = application.runningInstances
	float instances = application.instances

	if (application.state == AppState.STARTED && instances > 0) {
		float health = String.format('%.3f', runningInstances / instances).toFloat()
		if (health == 1) {
			return 'RUNNING'
		}
		return "${Math.round(health * 100)}%"
	}

	application.state == AppState.STOPPED ? 'STOPPED' : 'N/A'
}

deleteApplication = { boolean force, String name = getAppName() ->
	CloudApplication application = getApplication(name)

	def servicesToDelete = []
	for (String service in application.services) {
		if (force) {
			servicesToDelete << service
		}
		else {
			String answer = ask("Application '$application.name' uses '$service' service, would you like to delete it?",
				'y,n', 'y')
			if ('y'.equalsIgnoreCase(answer)) {
				servicesToDelete << service
			}
		}
	}

	client.deleteApplication application.name
	println "\nApplication '$application.name' deleted.\n"

	for (String service in servicesToDelete) {
		client.deleteService service
		println "Service '$service' deleted."
	}
}

findMemoryOptions = { ->
	CloudInfo cloudInfo = client.cloudInfo

	if (!cloudInfo.limits || !cloudInfo.usage) {
		return ['64M', '128M', '256M', '512M', '1G', '2G']
	}

	int availableForUse = cloudInfo.limits.maxTotalMemory - cloudInfo.usage.totalMemory
	if (availableForUse < 64) {
		checkHasCapacityFor 64
	}

	if (availableForUse < 128) return ['64M']
	if (availableForUse < 256) return ['64M', '128M']
	if (availableForUse < 512) return ['64M', '128M', '256M']
	if (availableForUse < 1024) return ['64M', '128M', '256M', '512M']
	if (availableForUse < 2048) return ['64M', '128M', '256M', '512M', '1G']
	['64M', '128M', '256M', '512M', '1G', '2G']
}

checkHasCapacityFor = { int memWanted ->
	CloudInfo cloudInfo = client.cloudInfo

	if (!cloudInfo.limits || !cloudInfo.usage) {
		return
	}

	int availableForUse = cloudInfo.limits.maxTotalMemory - cloudInfo.usage.totalMemory
	if (availableForUse < memWanted) {
		String totalMemory = prettySize(cloudInfo.limits.maxTotalMemory * 1024 * 1024)
		String usedMemory = prettySize(cloudInfo.usage.totalMemory * 1024 * 1024)
		String available = prettySize(availableForUse * 1024 * 1024)
		errorAndDie "Not enough capacity for operation.\nCurrent Usage: ($usedMemory of $totalMemory total, $available available for use)"
	}
}

buildWar = { ->
	File warfile
	if (argsMap.warfile) {
		warfile = new File(argsMap.warfile)
		if (warfile.exists()) {
			println "Using war file $argsMap.warfile"
		}
		else {
			errorAndDie "War file $argsMap.warfile not found"
		}
	}
	else {
		warfile = new File(grailsSettings.projectTargetDir, 'cf-temp-' + System.currentTimeMillis() + '.war')
		warfile.deleteOnExit()

		println 'Building war file'
		argsList.clear()
		argsList << warfile.path
		buildExplodedWar = false
		war()
	}

	warfile
}

memoryToMegs = { String memory ->
	if (memory.toLowerCase().endsWith('m')) {
		return memory[0..-2].toInteger()
	}

	if (memory.toLowerCase().endsWith('g')) {
		return memory[0..-2].toInteger() * 1024
	}

	memory.toInteger()
}

checkValidSelection = { int requested ->
	String formatted = prettySize(requested * 1024 * 1024, 0)

	def memoryOptions = findMemoryOptions()
	if (!memoryOptions.contains(formatted)) {
		errorAndDie "Invalid selection; $formatted must be one of $memoryOptions"
	}
}

checkDevelopmentEnvironment = { ->
	if ('development'.equals(grailsEnv) && !argsMap.warfile) {
		String answer = ask(
			"\nYou're running in the development environment but haven't specified a war file, so one will be built with development settings. Are you sure you want to do proceed?",
			'y,n', 'y')
		if ('n'.equalsIgnoreCase(answer)) {
			return false
		}
	}
	true
}

getAppName = { -> argsMap.appname ?: cfConfig.appname ?: grailsAppName }

displayInBanner = { names, things, renderClosures, lineBetweenEach = true ->

	def maxLengths = []
	for (name in names) {
		maxLengths << name.length()
	}

	for (thing in things) {
		renderClosures.eachWithIndex { render, index ->
			maxLengths[index] = Math.max(maxLengths[index], render(thing).toString().length())
		}
	}

	def divider = new StringBuilder()
	divider.append '+'
	maxLengths.each { length ->
		(length + 2).times { divider.append '-' }
		divider.append '+'
	}

	println ''
	println divider
	names.eachWithIndex { name, i ->
		print '| '
		print name.padRight(maxLengths[i])
		print ' '
	}
	println '|'
	println divider

	for (thing in things) {
		renderClosures.eachWithIndex { render, index ->
			print '| '
			print render(thing).toString().padRight(maxLengths[index])
			print ' '
		}
		println '|'
		if (lineBetweenEach) {
			println divider
		}
	}
	if (!lineBetweenEach) {
		println divider
	}

	println ''
}

createService = { ServiceConfiguration configuration, String serviceName = null ->
	if (!serviceName) {
		serviceName = "$configuration.vendor-${fastUuid()[0..6]}"
	}

	// TODO tier: 'free'
	client.createService new CloudService(
		name: serviceName, tier: 'free', type: configuration.type,
		vendor: configuration.vendor, version: configuration.version)

	println "Service '$serviceName' provisioned."

	serviceName
}

String fastUuid() {
	[0x0010000, 0x0010000, 0x0010000, 0x0010000, 0x0010000, 0x1000000, 0x1000000].collect {
		Integer.toHexString(new Random().nextInt(it))
	}.join('')
}

void createClient(String username, String password, String cloudControllerUrl) {
	def realClient = new CloudFoundryClient(username, password, null, new URL(cloudControllerUrl),
		GrailsHttpRequestFactory.newInstance())
	client = new ClientWrapper(realClient, GrailsHttpRequestFactory)
}

class ClientWrapper {

	private CloudFoundryClient realClient
	private GrailsHttpRequestFactory
	private Logger log = Logger.getLogger('grails.plugin.cloudfoundry.ClientWrapper')

	ClientWrapper(CloudFoundryClient client, Class requestFactoryClass) {
		realClient = client
		GrailsHttpRequestFactory = requestFactoryClass
	}

	def methodMissing(String name, args) {
		if (log.traceEnabled) log.trace "Invoking client method $name with args $args"

		GrailsHttpRequestFactory.resetResponse()
		try {
			if (args) {
				return realClient."$name"(*args)
			}
			else {
				return realClient."$name"()
			}
		}
		finally {
			logResponse()
		}
	}

	def propertyMissing(String name) {
		if (log.traceEnabled) log.trace "Invoking client property $name"

		GrailsHttpRequestFactory.resetResponse()
		try {
			return realClient."$name"
		}
		finally {
			logResponse()
		}
	}

	private logResponse() {
		if (!GrailsHttpRequestFactory.lastResponse || !log.debugEnabled) {
			return
		}

		try {
			log.debug "Last Request: ${new String(GrailsHttpRequestFactory.lastResponse)}"
		}
		catch (e) {
			GrailsUtil.deepSanitize e
			log.error e.message, e
		}
	}
}
