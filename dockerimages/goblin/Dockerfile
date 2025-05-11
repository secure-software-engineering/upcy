FROM maven:3.9.9-amazoncorretto-17


# Add the service itself
ARG JAR_FILE
ADD target/${JAR_FILE} /usr/share/myservice/myservice.jar
