const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({testDir:'./tests', timeout:30000, use:{baseURL:'http://127.0.0.1:4188',headless:true},webServer:{command:'python3 -m http.server 4188 --bind 127.0.0.1',port:4188,reuseExistingServer:false},reporter:'list'});
