#!groovy

/* 
 * Common application specific parameters
 * @APP_NAME: The name of the application. Cannot contains spaces and special characters.
 * @GIT_URL: The URL to the git repository.
 * @GIT_BRANCH: The branch to deploy
 * @BASE_IMAGE: Defines which base image is used for the application.
 * @BASE_IMAGE_TAG: The tag of the image. Prefer a specific version over latest.
 * @BASE_IMAGE_NAMESPACE: The namespace (case sensitive) where the base image resides. 
 */
APP_NAME="red-hat-sample"
GIT_URL="https://github.com/leskil/java-ci-demo"
GIT_BRANCH="master"
BASE_IMAGE="java"
BASE_IMAGE_TAG="8"
BASE_IMAGE_NAMESPACE="openshift"

/* 
 * Openshift environments
 * @BUILD_NAMESPACE: The project where Jenkins is installed and builds are created.
 * @DEV_NAMESPACE: The development project to promote the build to.
 * @IMAGE_STREAM_NAME: The name of the image stream created.
 * @BUILD_CONFIG_NAME: The name of the build config.
 */
BUILD_NAMESPACE="les-ci-test-build"
DEV_NAMESPACE="les-ci-test"
IMAGESTREAM_NAME="redhat-sample"
BUILD_CONFIG_NAME="redhat-sample"

/*
 * Additional settings.
 * @MVN_CMD: The Maven command to run. Handy if you are using an external configuration file.
 * @SONARQUBE_URL: The URL to Sonarqube. Leave it empty to disable Sonarqube.
 * @SONARQUBE_TOKEN: The access token for Sonarqube. Cannot be empty, if SONARQUBE_URL is used.
 * @NEXUS_URL: The URL to Nexus, where binaries are pushed to. Leave it empty to disable Nexus.
 */
MVN_CMD="mvn" // TODO: Use external config
SONARQUBE_URL=""
SONARQUBE_TOKEN=""
NEXUS_URL=""    

// Runtime variables - do not set here.
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

        /*
         * Delete local source files in the build container.
         */
        stage('Init') {

            steps {
                sh 'rm -rf src && mkdir src'
            }
        }

        /*
         * Pull the source code from Git.
         * Note: If needed, credentials should be created in Jenkins and passed to git using the 'credentials' parameter.
         */
        stage('Pull from Git') {
            steps {
                echo "Cloning branch '${GIT_BRANCH}' from ${GIT_URL}"
                git branch: GIT_BRANCH, url: GIT_URL
            }
        }

        /* 
         * Get the version information from the POM-file.
         * These version numbers are later used to tag outputs such as binaries and images.
         */
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

        /* 
         * Execution Maven build, but do no run unit tests yet - they will be executed in the next step.
         */
        stage('BUILD - execute Maven build') {
            steps {
                dir('src') {
                    echo "Executing Maven build"
                    sh "${MVN_CMD} clean package -DskipTests"
                }
            }
        }

        /*
         * Run unit tests and code analysis. Both can be run in parallel.
         * The code analysis step is only executed, if the SONARQUBE_URL is not empty.
         * If enabled, make sure that SONARQUBE_TOKEN contains the access token from Sonarqube.
         */ 
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

        /*
         * Push the compiled binaries to Nexus.
         * This step is only executed, if the NEXUS_URL variable is not empty.
         */
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
                script {
                    openshift.withCluster() {
                        openshift.withProject(BUILD_NAMESPACE) {

                            createImageStream(IMAGESTREAM_NAME, APP_NAME, BUILD_NAMESPACE)

                            def bc = openshift.selector("bc/${BUILD_CONFIG_NAME}")
                            if(!bc.exists()) {
                                def build_obj = openshift.process(readFile(file:'build/binary-s2i-template.yaml'),
                                                        '-p', "APP_NAME=${APP_NAME}",
                                                        '-p', "NAME=${BUILD_CONFIG_NAME}",
                                                        '-p', "BASE_IMAGESTREAM_NAMESPACE=${BASE_IMAGE_NAMESPACE}",
                                                        '-p', "BASE_IMAGESTREAM=${BASE_IMAGE}",
                                                        '-p', "BASE_IMAGE_TAG=${BASE_IMAGE_TAG}",
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

                            openshift.tag("${BUILD_NAMESPACE}/${IMAGESTREAM_NAME}:latest", "${BUILD_NAMESPACE}/${IMAGESTREAM_NAME}:${DEV_TAG}")
                            openshift.tag("${BUILD_NAMESPACE}/${IMAGESTREAM_NAME}:${DEV_TAG}", "${BUILD_NAMESPACE}/${IMAGESTREAM_NAME}:dev")
                        }
                    }
                }
            }
        }

        stage('DEV - Deploy to development environment') {
            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject(DEV_NAMESPACE) {
                            def dc = openshift.process(readFile(file:'build/dc-and-service.yaml'),
                                                '-p', "APPNAME=${APP_NAME}",
                                                '-p', "IMAGESTREAMNAMESPACE=${BUILD_NAMESPACE}",
                                                '-p', "IMAGESTREAM=${IMAGESTREAM_NAME}",
                                                '-p', "IMAGESTREAMTAG=dev",
                                                '-p', "SERVICEPORT=8080",
                                                '-p', "SERVICETARGETPORT=8080")

                            openshift.apply(dc)
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
            def isObj = openshift.process(readFile(file:'build/imagestream-template.yaml'), 
                    '-p', "APP_NAME=${appName}", 
                    '-p', "IMAGESTREAM_NAME=${name}", 
                    '-p', "REVISION=development")
            openshift.create(isObj)
        }
    }
}