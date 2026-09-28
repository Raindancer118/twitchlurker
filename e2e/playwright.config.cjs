module.exports = {
  testDir: '.',
  timeout: 60_000,
  use: { baseURL: 'http://127.0.0.1:18080', trace: 'retain-on-failure' },
  webServer: {
    command: 'bash ./start-app.sh',
    url: 'http://127.0.0.1:18080/actuator/health',
    timeout: 90_000,
    reuseExistingServer: false,
  },
};
