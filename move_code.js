const fs = require('fs');

const path = 'app/src/main/java/com/example/MainActivity.kt';
const content = fs.readFileSync(path, 'utf8');

const simStart = content.indexOf('        // 4.8. SIM Card Management Bento Card');
const themeStart = content.indexOf('        // 5. Theme Customizer Card');
const autoSwitchStart = content.indexOf('        // 4. Full-Width Light Blue Auto-Switch Ribbon Block');

if (simStart !== -1 && themeStart !== -1 && autoSwitchStart !== -1) {
    const simBlock = content.slice(simStart, themeStart);
    
    // remove simBlock from content
    let newContent = content.slice(0, simStart) + content.slice(themeStart);
    
    // However, the indices have changed since we removed a part. 
    // Is the extraction before or after autoSwitchStart?
    // autoSwitchStart is clearly before simStart (576 vs 1084).
    // So extracting from the back doesn't affect autoSwitchStart index!
    
    // we should recalculate autoSwitchStart on newContent to be safe
    const newAutoSwitchStart = newContent.indexOf('        // 4. Full-Width Light Blue Auto-Switch Ribbon Block');
    
    newContent = newContent.slice(0, newAutoSwitchStart) + simBlock + newContent.slice(newAutoSwitchStart);
    
    fs.writeFileSync(path, newContent, 'utf8');
    console.log("Success");
} else {
    console.log("Failed to find boundaries");
}
