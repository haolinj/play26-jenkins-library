/*
 * Toolform-compatible Jenkins 2 Pipeline build step for Play Framework 2.6 based components built using SBT
 */
// baseDir: "server",
// project: "source-ip",
// component: "public",
// buildNumber: buildNumber

def call(Map config) {
  final sbt = { cmd ->
    ansiColor('xterm') {
      dir(config.baseDir) {
        // todo: configure these in a single place referred to in persistent volumes template
        sh "sbt -J-Xmx2048m -batch -sbt-dir /home/jenkins/.sbt -Dsbt.repository.config=/home/jenkins/sbt.boot.properties -Dsbt.ivy.home=/home/jenkins/.ivy2/ -Divy.home=/home/jenkins/.ivy2/ -v \'${cmd}\'"
      }
    }
  }

  def fullComponentName = "${config.project}-${config.component}"
  def buildVersion = config.buildNumber
  def modulePath = "${config.baseDir}/modules/${config.subPath}"

  container('play26-sbt1-builder') {

    stage('Build Details') {
      echo "Project:   ${config.project}"
      echo "Component: ${config.component}"
      echo "BuildNumber: ${config.buildNumber}"
      echo "Module: ${config.module}"
      echo "S3 Bucket: ${config.bucket}"
      echo "AWS Cred ID: ${config.awsCredentialId}"
    }

    stage('Prepare environment') {
      writeFile(file: "/home/jenkins/sbt.boot.properties", 
        text: libraryResource('au/com/agiledigital/jenkins-pipelines/build-sbt-play26/sbt.boot.properties'))
    }

    stage('Compile') {
      sbt ";project ${config.get('module', config.component)}; compile"
    }
    
    stage('Compile Tests') {
      sbt ";project ${config.get('module', config.component)}; test:compile"
    }

    stage('Test') {
      script {
        try {
          sbt ";project ${config.get('module', config.component)}; testOnly * -- junitxml console"
        } finally {
          junit "${config.baseDir}/modules/**/target/test-reports/**/*.xml"
        }
      }
    }
  }

//  if(config.stage == 'dist') { 
    container('play26-sbt1-builder') {
      stage('Inject configuration') {
        // TODO: Allow ${SETTINGS_CONTEXT} to be overriden
          // From https://stash.agiledigital.com.au/projects/MCP/repos/docker-builder/browse/builders/play2-multi-build/build.sh
        sh """
        |# Insert the project.conf, environment.conf, etc into the deployable.
        |cp *.conf "${modulePath}/conf"
        |
        |# Create the conf file that ties the application.conf and environment.conf together.
        |echo 'include "application.conf"' > "${modulePath}/conf/combined.conf"
        |echo 'include "environment.conf"' >> "${modulePath}/conf/combined.conf"
        |echo 'include "topology.conf"' >> "${modulePath}/conf/combined.conf"
        |
        |# Allow the application.context variable to be overridden.
        |echo 'play.http.context=/' >> "${modulePath}/conf/combined.conf"
        |echo 'play.http.context=\${?APPLICATION_CONTEXT}' >> "${modulePath}/conf/combined.conf"
        |
        |# Allow cryto to be changed
        |echo 'play.crypto.secret=\${?APPLICATION_SECRET}' >> "${modulePath}/conf/combined.conf"
        |
        |cat "${modulePath}/conf/combined.conf"
        |
        |""".stripMargin()
      }
      stage('Package') {
        sbt ";project ${config.get('module', config.component)}; set name := \"${fullComponentName}\"; set version := \"${buildVersion}\"; dist"
      }
    }

    stage('Archive and publish to S3') {
      def tarName = "${fullComponentName}-${buildVersion}.tar.gz"
      
      // Re-compress dist zip file as tar gzip without top level folder
      sh "unzip ${modulePath}/target/universal/${fullComponentName}-${buildVersion}.zip"

      // Remove dist .bat, and rename main executable to have a generic name
      sh "rm \"${fullComponentName}-${buildVersion}/bin/${fullComponentName}.bat\""
      sh "mv \"${fullComponentName}-${buildVersion}/bin/${fullComponentName}\" \"${fullComponentName}-${buildVersion}/bin/dist\""
      sh "tar -czvf \"${tarName}\" -C \"${fullComponentName}-${buildVersion}\" ."
      
      withAWS(credentials: "${config.awsCredentialId}") {
        s3Upload(
          bucket: "${config.bucket}",
          path: "${config.project}/${config.branchName}/${config.buildNumber}/${config.component}/",
          file: tarName
        )
      }
    }
  // }
}
