# You will probably need to tweak some things in here, but this is to help you test the plugin on a real Jenkins instance
# At setup, it will have the released version of lockable-resources.
# Build a new hpi file with `mvn package` and then upload through the Jenkins UI. The hpi file is in the target directory.
# There will be a user `admin` with password `admin` created
# You only need to build the image and create a jenkins home dir once
docker build -t jenkins/locktest .
mkdir -p ./jenkins_home
chmod 777 ./jenkins_home

sudo docker run -p 8080:8080 -p 50000:50000 --name jenkins-localtest --rm -v ${PWD}/jenkins_home:/var/jenkins_home jenkins/locktest:latest
