const {test,expect}=require('@playwright/test');
const path=require('node:path');
const AxeBuilder=require('@axe-core/playwright').default;
for(const theme of ['dark','light']) for(const [device,width,height] of [['desktop',1440,1100],['mobile',390,844]]) {
 test(`${device} ${theme}: Screens, Layout und Barrierefreiheit`,async({page})=>{
 await page.setViewportSize({width,height}); await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/'); await page.evaluate(()=>document.fonts.ready);
 for(const screen of ['overview','channels','drops','raffles','bot','settings']){
 await page.locator(`nav a[href="#${screen}"]`).click();
 await expect(page.locator(`#${screen}`)).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await page.screenshot({path:path.join(__dirname,`../screenshots/${device}-${theme}-${screen}.png`),fullPage:true});
 const a11y=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
 expect(a11y.violations).toEqual([]);
 }
 expect(errors).toEqual([]);
 });
}
test('Mockaktionen, Details, Fehler- und Leerzustand',async({page})=>{
 await page.goto('/#channels');await page.locator('#channels').getByRole('button',{name:'Gronkh öffnen'}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.keyboard.press('Escape');
 await page.locator('nav a[href="#bot"]').click();await page.getByRole('button',{name:'Bot stoppen',exact:true}).click();await expect(page.locator('#bot-state')).toHaveText('Pausiert');await page.getByRole('button',{name:'Bot starten',exact:true}).click();await expect(page.locator('#bot-state')).toHaveText('Läuft');
 await page.getByLabel('Demo-Zustand').selectOption('expired');await expect(page.locator('#state-banner')).toContainText('Token abgelaufen');
 await page.getByLabel('Demo-Zustand').selectOption('empty');await page.locator('nav a[href="#overview"]').click();await expect(page.locator('#empty-state')).toBeVisible();
 await page.getByRole('button',{name:'Twitch verbinden',exact:true}).first().click();await expect(page.locator('#device-login')).toBeVisible();await page.getByRole('button',{name:'Verbindung simulieren'}).click();await expect(page.locator('#bot-state')).toHaveText('Läuft');
 await page.locator('nav a[href="#settings"]').click();await page.getByLabel('Zusätzliche Kanäle').fill('example_channel');await page.getByRole('button',{name:'Änderungen speichern'}).click();await page.reload();await expect(page.getByLabel('Zusätzliche Kanäle')).toHaveValue('example_channel');
});
test('Filter, Claims, Schalter, Tastatur und lokale Ressourcen',async({page})=>{
 const remote=[],errors=[];page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:4188'))remote.push(r.url());});page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto('/#channels');await page.getByLabel('Kanal suchen').fill('Gronkh');await expect(page.locator('.channel-row')).toHaveCount(1);await page.getByLabel('Kanal suchen').fill('Niemand');await expect(page.locator('#no-channels')).toBeVisible();await page.getByLabel('Kanal suchen').fill('');await page.locator('#channel-filter').selectOption('offline');await expect(page.locator('.channel-row')).toHaveCount(4);
 await page.locator('nav a[href="#drops"]').click();await page.getByRole('button',{name:'Belohnung claimen'}).click();await expect(page.locator('#claim-drop')).toBeDisabled();await page.locator('[data-drop-tab=inventory]').click();await expect(page.locator('.inventory-item')).toHaveCount(13);await expect(page.locator('#inventory')).toContainText('Reisetruhe des Entdeckers');
 await page.locator('nav a[href="#raffles"]').click();const toggle=page.getByRole('switch',{name:'Gronkh',exact:true});await toggle.uncheck();await page.reload();await expect(toggle).not.toBeChecked();
 await page.locator('nav a[href="#channels"]').click();await page.locator('#channels').getByRole('button',{name:'Gronkh öffnen'}).focus();await page.keyboard.press('Enter');await expect(page.getByRole('dialog')).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('#channels').getByRole('button',{name:'Gronkh öffnen'})).toBeFocused();
 expect(remote).toEqual([]);expect(errors).toEqual([]);
});
for(const theme of ['dark','light']) for(const [device,width,height] of [['desktop',1440,1100],['mobile',390,844]]) test(`${device} ${theme}: Sonderzustände`,async({page})=>{
 await page.setViewportSize({width,height});await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'});await page.goto('/#overview');
 for(const state of ['empty','expired']){await page.getByLabel('Demo-Zustand').selectOption(state);await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:path.join(__dirname,`../screenshots/${device}-${theme}-${state}.png`),fullPage:true});const a11y=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(a11y.violations).toEqual([]);}
 await page.getByLabel('Demo-Zustand').selectOption('normal');await page.locator('nav a[href="#channels"]').click();await page.locator('#channels').getByRole('button',{name:'Gronkh öffnen'}).click();await page.screenshot({path:path.join(__dirname,`../screenshots/${device}-${theme}-detail.png`),fullPage:false});expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);await page.keyboard.press('Escape');
 await page.locator('nav a[href="#drops"]').click();await page.locator('[data-drop-tab=inventory]').click();await page.screenshot({path:path.join(__dirname,`../screenshots/${device}-${theme}-inventory.png`),fullPage:true});
});
test('320px, Tablet und 4K ohne Überlauf; Bewegungsreduktion',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});
 for(const width of [320,768,1024,3840]){await page.setViewportSize({width,height:width===3840?2160:900});await page.goto('/');for(const screen of ['overview','channels','drops','raffles','bot','settings']){await page.locator(`nav a[href="#${screen}"]`).click();await expect(page.locator(`#${screen}`)).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();}if(width===3840){await page.locator('nav a[href="#overview"]').click();await expect(page.locator('#overview')).toBeVisible();await page.screenshot({path:path.join(__dirname,'../screenshots/4k-overview.png'),fullPage:true});}}
 expect(await page.evaluate(()=>getComputedStyle(document.body,'::before').animationName)).toBe('none');
});
