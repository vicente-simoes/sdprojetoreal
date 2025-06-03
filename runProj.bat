@echo off

REM Build the Java project using Maven
mvn clean compile assembly:single

REM Build the Docker image
docker build -t sd2425-trab2-65632-66197 .

REM Run the test with the image
test-sd-tp2.bat -image sd2425-trab2-65632-66197
