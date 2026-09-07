vm: template-vm

## Running inside the VM

Start the Spring Boot backend and Angular frontend dev servers:

    cd /workspace/backend && LLM_MOCK=true ./gradlew bootRun &
    cd /workspace/frontend && npm install && npm start -- --host 0.0.0.0 &
    # ready when http://localhost:4200 responds 200
