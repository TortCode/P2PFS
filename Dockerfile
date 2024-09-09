FROM openjdk:11
WORKDIR /user/app

COPY ./src ./src
COPY ./data ./data
RUN find ./src/ -type f -name "*.java" > sources.txt
RUN javac -d ./out/ @sources.txt
CMD java -cp ./out/ Main ./data/$DIRECTORY $TRACKER