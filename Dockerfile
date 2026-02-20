# 1. 베이스 이미지 설정 (JDK 포함된 이미지)
FROM eclipse-temurin:17-jre-jammy


# 2. 컨테이너 안에서 작업할 디렉토리
WORKDIR /app

# 3. 빌드된 JAR 파일을 컨테이너로 복사
#    PhoMate_BE/build/libs/ 안에 생성되는 jar를 app.jar로 복사
COPY build/libs/*.jar app.jar

# 4. 사용할 포트 문서화 (실제 개방은 docker run -p 에서)
EXPOSE 8080

# 5. 컨테이너가 실행될 때 돌릴 명령어
ENTRYPOINT ["java", "-jar", "app.jar"]

