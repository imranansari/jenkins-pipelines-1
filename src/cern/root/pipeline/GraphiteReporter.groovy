package cern.root.pipeline

import org.jenkinsci.plugins.plaincredentials.StringCredentials
import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.DomainRequirement

import java.io.DataOutputStream
import java.net.Socket
import java.util.Collections
import java.util.regex.Pattern

import hudson.security.ACL
import hudson.tasks.junit.TestResultAction
import jenkins.metrics.impl.TimeInQueueAction
import jenkins.model.Jenkins

/**
 * Reports build statistics to Graphite.
 */
class GraphiteReporter implements Serializable {
    private String graphiteServer
    private int graphiteServerPort
    private String graphiteMetricPath
    private def script
    private def mode

    /**
     * Creates a new GraphiteReporter.
     * The server configuration is read as secrets from Jenkins (secret text):
     *      graphite-server: Server hostname of the Graphite server.
     *      graphite-server-port: Server port of the Graphite server.
     *      graphite-metric-path: Metric path to send metrics to.
     * @param script Script context.
     * @param mode The build mode, e.g. continuous or experimental.
     */
    GraphiteReporter(script, mode) {
        this.script = script
        this.mode = mode
        this.graphiteServer = getSecret('graphite-server')
        this.graphiteServerPort = Integer.valueOf(getSecret('graphite-server-port'))
        this.graphiteMetricPath = getSecret('graphite-metric-path')
    }

    @NonCPS
    private def getSecret(secretId) {
        def creds = CredentialsMatchers.filter(
                CredentialsProvider.lookupCredentials(StringCredentials.class,
                        Jenkins.getInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(secretId)
        )

        if (creds.size() == 0) {
            throw new Exception("No key for secret $secretId found, did you forget registering such a secret?")
        }

        return creds.get(0).getSecret().getPlainText()
    }
    
    @NonCPS
    private def reportMetrics(metricName, metrics) {
        def connection = new Socket(graphiteServer, graphiteServerPort)
        def stream = new DataOutputStream(connection.getOutputStream())
        def metricPath = "${graphiteMetricPath}.${metricName}"
        def payload = "${metricPath} ${metrics.join(' ')}\n"

        stream.writeBytes(payload)
        connection.close()
    }

    /**
     * Reports a build and its statistics to Graphite.
     * @param build Build to report.
     */
    def reportBuild(build) {
        def now = (long)(System.currentTimeMillis() / 1000)
        def totalRunTime = System.currentTimeMillis() - build.getTimeInMillis()

        reportMetrics("build.${mode}.total_run_time", [(long)(totalRunTime / 1000), now])

        def action = build.getAction(TimeInQueueAction)
        if (action != null) {
            // Time it takes to actually build
            def buildTime = totalRunTime - action.getQueuingDurationMillis()

            reportMetrics("build.${mode}.run_time", [(long)(buildTime / 1000), now])
            reportMetrics("build.${mode}.queue_time", [(long)(action.getQueuingDurationMillis() / 1000), now])
        }

        def testResults = build.getAction(TestResultAction)

        if (testResults != null) {
            def failedTests = testResults.result.failedTests
            def skippedTests = testResults.result.skippedTests
            def passedTests = testResults.result.passedTests

            def totalTestCount = failedTests.size() + skippedTests.size() + passedTests.size()

            def platform = getPlatform(build)
            if (platform != null) {
                def buildName = "${script.VERSION}-$platform"

                reportMetrics("${buildName}.testresult.total", [totalTestCount, now])
                reportMetrics("${buildName}.testresult.passed", [passedTests.size(), now])
                reportMetrics("${buildName}.testresult.skipped", [skippedTests.size(), now])
                reportMetrics("${buildName}.testresult.failed", [failedTests.size(), now])

                for (test in passedTests) {
                    def title = test.name.replace('.', '-')
                    reportMetrics("${buildName}.tests.${title}", [0, now])
                }

                for (test in skippedTests) {
                    def title = test.name.replace('.', '-')
                    reportMetrics("${buildName}.tests.${title}", [1, now])
                }

                for (test in failedTests) {
                    def title = test.name.replace('.', '-')
                    reportMetrics("${buildName}.tests.${title}", [2, now])
                }
            }
        }
    }


    @NonCPS
    private def getPlatform(build) {
        def platform = null
        def pattern = Pattern.compile('\\+*\\sPLATFORM=(?<platform>.*)')
        
        build.logReader.withReader {
            def line = null
            while ((line = it.readLine()) != null) {
                def matcher = pattern.matcher(line)
                if (matcher.find()) {
                    platform = matcher.group('platform')
                }
            }
        }

        if (platform == null) {
            script.println 'WARNING: No platform was found for this build. Did jenkins/getPlatform.py get executed?'
        }
        
        return platform
    }
}
