/* 
 *  1. Skal der laves noget init? Ryddes op i build containeren? Findes versionsnumre?
 *  2. SCM checkout
 *  3. Maven build (uden tests)
 *  4. Paralelt: Kør unit tests og code analysis
 *  5. Publish to Nexus
 *  6. 
 */
 GIT_URL="https://github.com/leskil/java-ci-demo"

pipeline {
    agent {
        label 'maven'
    }

    stages {

        // Delete local source files in the build container
        stage('Init') {

            steps {
                sh 'rm -rf src && mkdir src'
            }
        }

        stage('Checkout from SCM') {
            steps {
                dir('src') {

                    //echo sh 'pwd'

                    script {
                        def mvnCmd = "mvn"
                        def version = getVersionFromPom("pom.xml")
                        def devTag  = "${version}-${currentBuild.number}"
                        echo "Dev tag: ${devTag}"
                    }                    
                }
            }
        }

    }

}

def getVersionFromPom(pom) {
  def matcher = readFile(pom) =~ '<version>(.+)</version>'
  matcher ? matcher[0][1] : null
}