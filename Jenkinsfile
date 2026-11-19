pipeline {
  agent any
  environment {
    TOMCAT_WEBAPPS = '/opt/tomcat9/webapps'   // Tomcat 9 path on the EC2 box
    TOMCAT_PORT    = '9090'
    APP            = 'reelboost'
  }
  options { timestamps(); disableConcurrentBuilds() }
  stages {
    stage('Checkout') { steps { checkout scm } }
    stage('Tool check') {
      steps { sh '''
        java -version
        mvn -v
        if command -v ffmpeg >/dev/null 2>&1; then ffmpeg -version | head -1; else echo "WARNING: ffmpeg is not installed on this server (the app needs it at runtime)"; fi
      ''' }
    }
    stage('Build + Unit tests') { steps { sh 'mvn -B clean verify' } }
    stage('Verify WAR') { steps { sh 'test -f target/${APP}.war && unzip -l target/${APP}.war | grep -q "WEB-INF/classes/com/reelboost/web/ApiServlet.class"' } }
    stage('Archive') {
      steps {
        junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
        archiveArtifacts artifacts: 'target/*.war', fingerprint: true
      }
    }
    stage('Deploy to Tomcat 9') {
      steps { sh '''
        cp target/${APP}.war "$TOMCAT_WEBAPPS/${APP}.war"
        for i in $(seq 1 40); do
          if curl -fs "http://localhost:$TOMCAT_PORT/${APP}/health" | tee /tmp/${APP}-health.json | grep -q '"status":"ok"'; then
            echo; cat /tmp/${APP}-health.json; echo " <- deployed"
            grep -q '"ffmpeg":true' /tmp/${APP}-health.json || echo "WARNING: app is up but ffmpeg is missing: sudo apt install -y ffmpeg"
            exit 0
          fi
          sleep 3
        done
        echo "Health check failed. See /opt/tomcat9/logs/catalina.out"; exit 1
      ''' }
    }
  }
  post { success { echo 'Open: http://<EC2-IP>:9090/reelboost/' } }
}
