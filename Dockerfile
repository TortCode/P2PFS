FROM openjdk:11
WORKDIR /user/app/

COPY ./src/ ./src/
RUN find ./src/ -type f -name "*.java" > sources.txt
RUN javac -d ./out/ @sources.txt
CMD java -cp ./out/ pfs.Main /data/$DIRECTORY $TRACKER
