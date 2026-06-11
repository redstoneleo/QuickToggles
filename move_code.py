import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    lines = f.readlines()

# The SIM Card Management Bento Card is from line 1084 to 1354 (0-indexed 1083 to 1353)
# The Auto-Switch block is at line 576 (0-indexed 575)

# Find the exact boundaries:
sim_start = -1
sim_end = -1
auto_start = -1

for i, line in enumerate(lines):
    if '// 4.8. SIM Card Management Bento Card' in line:
        sim_start = i
    elif '// 5. Theme Customizer Card (Modular Color Pickers)' in line:
        sim_end = i - 1 # the empty line before 5.
    elif '// 4. Full-Width Light Blue Auto-Switch Ribbon Block' in line:
        auto_start = i

print(f"sim_start: {sim_start}, sim_end: {sim_end}, auto_start: {auto_start}")

if sim_start != -1 and sim_end != -1 and auto_start != -1:
    sim_block = lines[sim_start:sim_end + 1]
    
    # Remove the sim_block from the original place
    del lines[sim_start:sim_end + 1]
    
    # Insert at auto_start
    # Note that auto_start index shifts depending on whether sim_start was before or after auto_start.
    # In this case sim_start > auto_start, so inserting before it doesn't shift auto_start index.
    
    lines = lines[:auto_start] + sim_block + lines[auto_start:]
    
    with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
        f.writelines(lines)
    
    print("Code moved successfully.")
else:
    print("Could not find the appropriate blocks.")
