#!groovy
/* 
 *  1. Skal der laves noget init? Ryddes op i build containeren? Findes versionsnumre?
 *  2. SCM checkout
 *  3. Maven build (uden tests)
 *  4. Paralelt: Kør unit tests og code analysis
 *     SonarQube token: 2dad05e4a4f5c056ee9f83760fa8e6cdc079b8b4
 *  5. Publish to Nexus
 *  6. Create image in build namespace
 *  7. Deploy image to dev namespace
 *  8. (Run tests against dev namespace)
 *  9. Deploy same image to test namespace
 */

// Common parameters
APP_NAME="Red Hat Sample"

// SCM parameters
GIT_URL="https://github.com/leskil/java-ci-demo"
GIT_BRANCH="master"

// Environments
BUILD_NAMESPACE="build"
DEV_NAMESPACE="dev"
TEST_NAMESPACE="test"
IMAGESTREAM_NAME=APP_NAME
BUILD_CONFIG_NAME=APP_NAME

// Convinience variables
MVN_CMD="mvn" // TODO: Use external config
SONARQUBE_URL="" //"https://sonarqube-sonarqube.apps.na311.openshift.opentlc.com" // Leave empty to disable
SONARQUBE_TOKEN="2dad05e4a4f5c056ee9f83760fa8e6cdc079b8b4"
NEXUS_URL="" // Leave empty to disable

// Runtime variables
DEV_TAG=""
PROD_TAG=""


pipeline {
    agent {
        label 'maven'
    }

    options {
        skipStagesAfterUnstable()
    }

    stages {

        // Delete local source files in the build container
        stage('Init') {

            steps {
                sh 'rm -rf src && mkdir src'
            }
        }

        // Pull the source code from Git
        stage('Pull from Git') {
            steps {
                echo "Cloning branch '${GIT_BRANCH}' from ${GIT_URL}"
                git branch: GIT_BRANCH, url: GIT_URL
            }
        }

        // Get the version information from the POM-file
        stage('Get version information') {
            steps {
                dir('src') {
                    script {
                        def pom = readMavenPom file: 'pom.xml'
                        DEV_TAG  = "${pom.version}-${currentBuild.number}"
                        PROD_TAG = pom.version
                        echo "Dev tag: ${DEV_TAG}. Prod tag: ${PROD_TAG}"
                    }                    
                }                
            }
        }

        // Execution Maven build, but do no run unit tests yet
        stage('BUILD - execute Maven build') {
            steps {
                dir('src') {
                    echo "Executing Maven build"
                    sh "${MVN_CMD} clean package -DskipTests"
                }
            }
        }

        // Run unit tests and code analysis. Both can be run in parallel
        stage('BUILD - run unit tests and code analysis in parallel') {
            steps {
                parallel(
                    tests: {
                        dir ('src') {
                            echo "Running tests"
                            sh "${MVN_CMD} test"
                        }
                    },
                    codeanalysis: {
                        script {
                            if (SONARQUBE_URL) {
                                dir ('src') {                        
                                    echo "Running code analysis"
                                    sh "${MVN_CMD} sonar:sonar -Dsonar.host.url=${SONARQUBE_URL} -Dsonar.login=${SONARQUBE_TOKEN} -Dsonar.projectVersion=${DEV_TAG} -Dsonar.projectName=\"${APP_NAME}\""
                                }
                            } else {
                                echo "SONARQUBE_URL is empty - skipping code analysis"
                            }
                        }
                    }
                )
            }
        }

        // Push the binaries to Nexus
        stage('BUILD - Publish to Nexus') {
            steps {
                script {
                    if (NEXUS_URL) {
                        dir('src') {
                            echo "Publishing binaries to Nexus ${NEXUS_URL}"
                            sh "${MVN_CMD} deploy -DskipTests=true -DaltDeploymentRepository=nexus::default::${NEXUS_URL}"
                        }
                    } else {
                        echo "NEXUS_URL is empty - skipping publish"
                    }
                }
            }
        }

        /*
         *  Create a new image from the source code in the build namespace.
         *  This will create a new build config, if one does not already exist. 
         */
        stage('BUILD - Create image in build namespace') {
            steps {
                dir('src') {
                    script {
                        openshift.withProject(DEV_NAMESPACE) {

                            createImageStream(IMAGESTREAM_NAME, APP_NAME, DEV_NAMESPACE)

                            def bc = openshift.selector("bc/${BUILD_CONFIG_NAME}")
                            if(!bc.exists()) {
                            def build_obj = openshift.process(readFile(file:'build/binary-s2i-template.yaml'),
                                                    '-p', "APP_NAME=${APP_NAME}",
                                                    '-p', "NAME=${BUILD_CONFIG_NAME}",
                                                    '-p', "BASE_IMAGESTREAM_NAMESPACE=${BUILD_NAMESPACE}",
                                                    '-p', "BASE_IMAGESTREAM=${IMAGESTREAM_NAME}",
                                                    '-p', "BASE_IMAGE_TAG=${DEV_TAG}",
                                                    '-p', "TARGET_IMAGESTREAM=${IMAGESTREAM_NAME}",
                                                    '-p', "REVISION=development")

                            openshift.create(build_obj)
                            }

                            bc.startBuild('--from-dir=src/target/')
                            def builds = bc.related('builds')
                            timeout(60) {
                                builds.untilEach(1) {
                                    return it.object().status.phase == 'Complete'
                                }
                            }

                            openshift.tag("${DEV_NAMESPACE}/${IMAGESTREAM_NAME}:latest", "${DEV_NAMESPACE}/${IMAGESTREAM_NAME}:${DEV_TAG}")
                        }
                    }
                }
            }
        }

    }

}

/**
 * Create image stream if one does not exist
 *
 * @param name name of imagestream to create
 * @param appName name of application, for labeling the imagestream
 * @param namespace project/namespace name
 */
def createImageStream(name, appName, namespace) {
    openshift.withProject(namespace) {
        def is = openshift.selector('is', name);
        if(!is.exists()) {
            def isObj = openshift.process(readFile(file:'src/openshift/templates/imagestream-template.yaml'), 
                    '-p', "APP_NAME=${appName}", 
                    '-p', "IMAGESTREAM_NAME=${name}", 
                    '-p', "REVISION=development")
            openshift.create(isObj)
        }
    }
}