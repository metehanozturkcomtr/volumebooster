import React, {useState, useEffect, useCallback} from 'react';
import {
  NativeModules,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  Linking,
  Platform,
  Appearance,
  DeviceEventEmitter, // Added
  Modal, // Added for Help Pop-up
  // Image, // Removed
} from 'react-native';
import Slider from '@react-native-community/slider';
import MaterialCommunityIcons from 'react-native-vector-icons/MaterialCommunityIcons'; // Import vector icons

// Import native module
const {AudioModule} = NativeModules;
// Media key codes removed

// Helper Functions
const mBToPercent = (mbValue: number, maxMb: number): number => {
  if (maxMb <= 0) return 0; // Prevent division by zero
  return Math.round((mbValue / maxMb) * 100);
};

const percentToMb = (percentValue: number, maxMb: number): number => {
  return Math.round((percentValue / 100) * maxMb);
};


function App(): React.JSX.Element {
  console.log('App component rendering...'); // Add log here

  // States
  const [isDarkModeEnabled, setIsDarkModeEnabled] = useState(Appearance.getColorScheme() === 'dark'); // Explicit state for dark mode
  const [boostLevel, setBoostLevel] = useState(0); // In mB (for communication with Native)
  const [maxBoostLevelMb, setMaxBoostLevelMb] = useState(1000); // Initial value, will be obtained from native later
  const [systemVolume, setSystemVolume] = useState(0);
  const [maxSystemVolume, setMaxSystemVolume] = useState(1); // 1 to prevent division by zero
  const [isServiceRunning, setIsServiceRunning] = useState(false); // To track service status (optional)
  // isPlayingHint and isMusicSystemActive states removed

  // State for help modal visibility
  const [isHelpModalVisible, setIsHelpModalVisible] = useState(false);


  // Optional: Effect to update dark mode state if system theme changes
  // useEffect(() => {
  //   const subscription = Appearance.addChangeListener(({ colorScheme }) => {
  //     setIsDarkModeEnabled(colorScheme === 'dark');
  //   });
  //   return () => subscription.remove();
  // }, []);


  // Function to handle developer link click
  const handleDeveloperLink = () => {
    Linking.openURL('https://metehanozturk.com.tr').catch(err =>
      console.error('Could not open link:', err), // Translated log
    );
  };

  // --- Native Module Calls ---

  // Set boost level (now takes percentage and converts to mB)
  const handleBoostLevelChange = useCallback(async (percentValue: number) => {
    const levelMb = percentToMb(percentValue, maxBoostLevelMb);
    setBoostLevel(levelMb); // Update state in mB
    try {
      await AudioModule.setBoostLevel(levelMb); // Send mB to native module
      console.log(`Boost level set to: ${percentValue}% (${levelMb} mB)`);
    } catch (error) {
      console.error('Failed to set boost level:', error);
    }
  }, [maxBoostLevelMb]); // Recreate function if maxBoostLevelMb changes

  // Set system volume (debounce could be added)
  const handleSystemVolumeChange = useCallback(async (volume: number) => {
    const intVolume = Math.round(volume);
    setSystemVolume(intVolume); // Update slider immediately
    try {
      await AudioModule.setSystemVolume(intVolume);
      console.log('System volume set to:', intVolume);
    } catch (error) {
      console.error('Failed to set system volume:', error);
    }
  }, []);

  // Quick toggle (between 0 and last level)
  const handleToggleBoost = async () => {
    try {
      // Call the native module's toggleBoost method
      // Call the native module's toggleBoost method. State will be updated via DeviceEventEmitter.
      await AudioModule.toggleBoost();
      // setBoostLevel(expectedNewLevel); // REMOVED: State update now relies on BoostLevelChanged event
      console.log('Boost toggle initiated.'); // Updated log message
    } catch (error) {
      console.error('Failed to toggle boost:', error);
    }
  };

   // Exit App
   const handleExitApp = async () => {
     try {
       await AudioModule.exitApp(); // Call native module to handle exit
       console.log('Exit command sent.');
       // Optional: Close the RN app directly, though the service should handle activity finish
       // import { BackHandler } from 'react-native';
       // BackHandler.exitApp();
     } catch (error) {
       console.error('Failed to send exit command:', error);
     }
   };

  // handleMediaKey function removed


  // --- Application Initialization ---
  useEffect(() => {
    console.log('useEffect running...'); // Log start

    const initializeApp = async () => {
      console.log('initializeApp starting...'); // Log initApp start

      let permissionGranted = true;
      if (Platform.OS === 'android' && Platform.Version >= 33) {
        try {
          permissionGranted = await AudioModule.requestNotificationPermission();
          console.log('Notification permission granted:', permissionGranted);
        } catch (error) {
          console.error('Failed to request notification permission:', error);
          permissionGranted = false;
        }
      }

      if (permissionGranted) {
        try {
          await AudioModule.startBoosterService();
          setIsServiceRunning(true);
          console.log('Booster service started.');
        } catch (error) {
          console.error('Failed to start booster service:', error);
          setIsServiceRunning(false);
        }
      } else {
         setIsServiceRunning(false);
      }

      try {
        console.log('Fetching initial audio states...'); // Log before Promise.all
        const [
          initialBoostLevel,
          initialMaxBoostLevel,
          initialSystemVolume,
          initialMaxSystemVolume,
        ] = await Promise.all([
          AudioModule.getBoostLevel(),
          AudioModule.getMaxBoostLevel(),
          AudioModule.getSystemVolume(),
          AudioModule.getMaxSystemVolume(),
        ]);
        console.log('Initial audio states fetched:', { initialBoostLevel, initialMaxBoostLevel, initialSystemVolume, initialMaxSystemVolume }); // Log fetched values

        let finalInitialBoostLevel = initialBoostLevel;
        if (initialBoostLevel === 0) {
          try {
            const lastActiveLevel = await AudioModule.getLastActiveBoostLevel();
            if (lastActiveLevel > 0) {
              console.log('Booster was off, activating to last active level:', lastActiveLevel);
              await AudioModule.setBoostLevel(lastActiveLevel);
              finalInitialBoostLevel = lastActiveLevel; // Restore this assignment
            }
          } catch (error) {
            console.error('Failed to get or set last active boost level:', error);
          }
        }

        console.log('Setting initial states...'); // Log before setting states
        setBoostLevel(finalInitialBoostLevel); // Use finalInitialBoostLevel again
        setMaxBoostLevelMb(initialMaxBoostLevel > 0 ? initialMaxBoostLevel : 1000);
        setSystemVolume(initialSystemVolume);
        setMaxSystemVolume(initialMaxSystemVolume > 0 ? initialMaxSystemVolume : 1);
        console.log('Initial states set.'); // Log after setting states

      } catch (error) {
        console.error('Failed to get initial audio states:', error);
      }

      // Force notification update after initialization is complete
      try {
        await AudioModule.forceUpdateNotification();
        console.log('Forced notification update after initialization.');
      } catch (error) {
        console.error('Failed to force notification update:', error);
      }

      console.log('initializeApp finished.'); // Log initApp end
    };

    initializeApp(); // Call the initialization function

    // Setup listeners
    console.log('Setting up event listeners...'); // Log listener setup
    const boostEventListener = DeviceEventEmitter.addListener('BoostLevelChanged', (event) => {
      console.log('BoostLevelChanged event received:', event);
      if (event && typeof event.boostLevel === 'number') {
        setBoostLevel(event.boostLevel);
      }
    });

    const volumeEventListener = DeviceEventEmitter.addListener('SystemVolumeChanged', (event) => {
      console.log('SystemVolumeChanged event received:', event);
      if (event && typeof event.systemVolume === 'number') {
        setSystemVolume(event.systemVolume);
      }
    });
    console.log('Event listeners set up.'); // Log listener setup end

    // Cleanup function
    return () => {
      console.log('useEffect cleanup: Removing listeners...'); // Log cleanup start
      boostEventListener.remove();
      volumeEventListener.remove();
      console.log('Listeners removed.'); // Log cleanup end
    };
  }, []); // Empty dependency array ensures this runs only once on mount


  // --- UI Related ---

  // Toggle Dark Mode Function
  const toggleDarkMode = () => {
    setIsDarkModeEnabled(previousState => !previousState);
  };

  // Define styles dependent on isDarkModeEnabled *inside* the component
  const backgroundStyle = {
    backgroundColor: isDarkModeEnabled ? '#121212' : '#F5F5F5',
    flex: 1,
  };

  const textStyle = {
    color: isDarkModeEnabled ? '#FFFFFF' : '#000000',
  };

  const developerTextStyle = {
    color: isDarkModeEnabled ? '#AAAAAA' : '#555555',
  };

  const helpButtonStyle = {
    ...styles.cornerButtonBase, // Use a common base style
    ...styles.helpButtonPosition,
    backgroundColor: isDarkModeEnabled ? '#555' : '#DDD',
  };

  const helpIconTextStyle = {
    ...styles.cornerIconTextBase,
    color: isDarkModeEnabled ? '#FFF' : '#000',
  };

  const darkModeButtonStyle = {
    ...styles.cornerButtonBase, // Use a common base style
    ...styles.darkModeButtonPosition,
    backgroundColor: isDarkModeEnabled ? '#555' : '#DDD',
  };

  const darkModeIconTextStyle = {
     ...styles.cornerIconTextBase,
     // Emoji color is usually fixed
  };

  const modalContentStyle = {
      ...styles.modalContentBase,
      backgroundColor: isDarkModeEnabled ? '#222' : '#FFF'
  };

  const modalTitleStyle = {
      ...styles.modalTitleBase,
      ...textStyle // Apply dynamic text color
  };

   const modalTextStyle = {
      ...styles.modalTextBase,
      ...textStyle // Apply dynamic text color
  };

  const footerTextStyle = {
      ...styles.footerTextBase,
      ...developerTextStyle // Use developer text color for footer
  };


  console.log('App component returning JSX...'); // Add log here
  return (
    <SafeAreaView style={backgroundStyle}>
      <StatusBar
        barStyle={isDarkModeEnabled ? 'light-content' : 'dark-content'}
        backgroundColor={backgroundStyle.backgroundColor}
      />
      <View style={styles.container}>
         {/* Dark Mode Toggle Button */}
         <TouchableOpacity
           style={darkModeButtonStyle}
           onPress={toggleDarkMode}>
           <Text style={darkModeIconTextStyle}>
             {isDarkModeEnabled ? '🌙' : '☀️'}
           </Text>
         </TouchableOpacity>

        {/* Help Button */}
        <TouchableOpacity
          style={helpButtonStyle}
          onPress={() => setIsHelpModalVisible(true)}>
          <Text style={helpIconTextStyle}>?</Text>
        </TouchableOpacity>

        {/* Main Content Area */}
        <View style={styles.mainContent}>
          <Text style={[styles.title, textStyle]}>Volume Booster</Text>
          <Text style={[styles.subtitle, developerTextStyle]}>-by MTHN</Text>

           {/* System Volume Slider */}
           <Text style={[styles.label, textStyle]}>
             System Volume: {Math.round((systemVolume / maxSystemVolume) * 100)}%
           </Text>
           <Slider
             style={styles.slider} // Keep height: 80 from previous request
             minimumValue={0}
             maximumValue={maxSystemVolume}
             step={1}
             value={systemVolume}
             onSlidingComplete={handleSystemVolumeChange}
             minimumTrackTintColor={isDarkModeEnabled ? '#03DAC6' : '#018786'}
             maximumTrackTintColor={isDarkModeEnabled ? '#333333' : '#CCCCCC'}
             thumbTintColor={isDarkModeEnabled ? '#03DAC6' : '#018786'}
           />

          {/* Boost Level Slider (Percentage Display) */}
          <Text style={[styles.label, textStyle]}>
            Boost Level: {mBToPercent(boostLevel, maxBoostLevelMb)}%
          </Text>
          <Slider
            style={styles.slider} // Keep height: 80 from previous request
            minimumValue={0} // 0%
            maximumValue={100} // 100%
            step={5} // 5% steps (optional)
            value={mBToPercent(boostLevel, maxBoostLevelMb)} // Convert value to percentage
            onSlidingComplete={handleBoostLevelChange}
            minimumTrackTintColor={mBToPercent(boostLevel, maxBoostLevelMb) > 75 ? '#FF0000' : (isDarkModeEnabled ? '#BB86FC' : '#6200EE')} // Red above 75%
            maximumTrackTintColor={isDarkModeEnabled ? '#333333' : '#CCCCCC'}
            thumbTintColor={isDarkModeEnabled ? '#BB86FC' : '#6200EE'}
          />
          {/* Warning Text */}
          {/* Warning Text - Always rendered, opacity controlled */}
             <View style={[
                styles.warningContainer,
                { opacity: mBToPercent(boostLevel, maxBoostLevelMb) > 75 ? 1 : 0 } // Control visibility with opacity
             ]}>
               <Text style={styles.warningTitle}>WARNING:</Text>
               <Text style={styles.warningBody}>
                 Excessively high volume levels may damage your hearing or device.
               </Text>
             </View>
          {/* Removed conditional rendering closing brace */}
          {/* Stray parenthesis removed */}

          {/* Quick Toggle Button */}
          <TouchableOpacity
            style={[
              styles.button,
              boostLevel === 0 && styles.buttonDisabled,
              // Border style removed
            ]}
            onPress={handleToggleBoost}>
            {/* Text and Icon side-by-side */}
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <Text style={[
                  styles.buttonText,
                  { color: boostLevel > 0 ? '#BDBDBD' : '#6200EE', marginRight: 5 } // Add margin to text
              ]}>
                {boostLevel > 0 ? 'ON' : 'OFF'}
              </Text>
              <MaterialCommunityIcons
                name={boostLevel > 0 ? "volume-high" : "volume-off"}
                size={24} // Adjust icon size as needed
                color={boostLevel > 0 ? '#BDBDBD' : '#6200EE'} // Use conditional color
              />
            </View>
          </TouchableOpacity>

          {/* Exit Button (Updated Style) */}
          <TouchableOpacity
            style={styles.exitButton} // Temporarily remove styles.button for testing
            onPress={handleExitApp}>
            {/* Use vector icon instead of Text */}
            <MaterialCommunityIcons
              name="power-standby"
              size={styles.exitButtonText.fontSize} // Use size from existing style
              color={isDarkModeEnabled ? '#FFFFFF' : '#000000'} // Dynamic color based on theme
            />
          </TouchableOpacity>

        </View>

        {/* Developer Info */}
        <TouchableOpacity
          style={styles.developerContainer}
          onPress={handleDeveloperLink}>
          <Text style={[styles.developerText, developerTextStyle]}>
            Developer: Metehan Öztürk
          </Text>
        </TouchableOpacity>

        {/* Help Modal (English Text) */}
        <Modal
          animationType="fade"
          transparent={true}
          visible={isHelpModalVisible}
          onRequestClose={() => setIsHelpModalVisible(!isHelpModalVisible)}>
          <View style={styles.modalContainer}>
            <View style={modalContentStyle}> {/* Dynamic background */}
              <Text style={modalTitleStyle}>Help</Text> {/* Dynamic text color */}
              <Text style={modalTextStyle}> {/* Dynamic text color */}
                <Text style={{fontWeight: 'bold'}}>Home Screen Widget:</Text>
                {'\n'}
                Long-press on your home screen, select the option to add widgets, and find the "Volume Booster" widget. You can quickly toggle the boost on/off from the widget.
              </Text>
              <Text style={modalTextStyle}> {/* Dynamic text color */}
                <Text style={{fontWeight: 'bold'}}>Quick Settings Tile:</Text>
                {'\n'}
                Pull down the notification shade fully (sometimes requires two swipes or pulling from a specific side), tap the edit (pencil) icon, find the "Volume Booster" tile, and drag it to your active tiles. You can also toggle the boost from here.
              </Text>
              <TouchableOpacity
                style={styles.closeButton}
                onPress={() => setIsHelpModalVisible(false)}>
                <Text style={styles.closeButtonText}>Close</Text>
              </TouchableOpacity>
              {/* Footer Text inside Modal */}
              <Text style={[styles.footerTextBase, developerTextStyle, {marginTop: 15}]}>
                Thank you for using the app! 🙏
              </Text>
            </View>
          </View>
        </Modal>

        {/* Footer Text Removed from main screen */}

      </View>
    </SafeAreaView>
  );
}

// StyleSheet with base styles (no dynamic references)
const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 60, // Increased padding for top buttons
    paddingBottom: 10, // Padding for footer
  },
  cornerButtonBase: { // Common style for top corner buttons
    position: 'absolute',
    top: 15,
    width: 30,
    height: 30,
    borderRadius: 15,
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
    elevation: 5,
  },
  darkModeButtonPosition: { // Specific position for dark mode button
     left: 15,
  },
  helpButtonPosition: { // Specific position for help button
     right: 15,
  },
  cornerIconTextBase: { // Common text style for corner buttons
     fontSize: 18,
  },
  mainContent: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    width: '100%',
  },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
  },
  subtitle: {
    fontSize: 14,
    marginBottom: 20,
    fontStyle: 'italic',
  },
  label: {
    fontSize: 16,
    marginTop: 20,
    marginBottom: 5,
  },
  slider: {
    width: '90%',
    height: 80, // Keep increased height
    marginBottom: 15,
  },
  button: {
    marginTop: 30,
    backgroundColor: '#6200EE', // Default button color
    paddingVertical: 15,
    paddingHorizontal: 30,
    // borderRadius: 25, // Removed for rectangular shape
    elevation: 3,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.2,
    shadowRadius: 2,
  },
  buttonDisabled: {
    backgroundColor: '#BDBDBD',
    elevation: 1,
    shadowOpacity: 0.1,
  },
  exitButton: { // Updated exit button style
    // backgroundColor: '#888888', // Removed for transparent background
    marginTop: 30, // Increased margin to move button down
    // paddingVertical: 6, // Remove padding for icon button
    // paddingHorizontal: 15, // Remove padding for icon button
    width: 40, // Set fixed width
    height: 40, // Set fixed height
    borderRadius: 20, // Make it circular
    // elevation: 1, // Removed as background is transparent
    justifyContent: 'center', // Center content vertically
    alignItems: 'center', // Center content horizontally
    // borderWidth: 2, // Border removed
    // borderColor: '#FF8888', // Border removed
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: 'bold',
  },
  exitButtonText: { // Specific style for Exit icon
    color: '#FFFFFF', // White color
    fontSize: 24, // Larger font size for icon
    fontWeight: 'bold', // Keep bold
    textAlign: 'center', // Ensure text is centered if needed
    // Include line height equal to font size for better vertical centering in some cases
    lineHeight: 24,
  },
  warningContainer: {
    marginTop: 10,
    paddingHorizontal: 20,
    alignItems: 'center',
    // minHeight: 45, // Removed, using opacity instead
  },
  warningTitle: {
    fontSize: 14,
    fontWeight: 'bold',
    color: '#FFA500',
    marginBottom: 2,
    opacity: 0.7,
  },
  warningBody: {
    fontSize: 12,
    color: '#FF0000',
    textAlign: 'center',
    opacity: 0.7,
  },
  developerContainer: {
    marginTop: 15,
    marginBottom: 15,
  },
  developerText: {
    fontSize: 12,
    textDecorationLine: 'underline',
  },
  // Modal Styles
  modalContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
  },
  modalContentBase: { // Base style for modal content
    width: '85%',
    padding: 20,
    borderRadius: 10,
    alignItems: 'center',
    elevation: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
  },
  modalTitleBase: { // Base style for modal title
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 15,
  },
  modalTextBase: { // Base style for modal text
    fontSize: 14,
    textAlign: 'left',
    marginBottom: 15,
    alignSelf: 'stretch',
  },
  closeButton: {
    marginTop: 10,
    backgroundColor: '#6200EE',
    paddingVertical: 10,
    paddingHorizontal: 25,
    borderRadius: 20,
  },
  closeButtonText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: 'bold',
  },
  footerTextBase: { // Base style for footer text (now used in modal)
    fontSize: 12,
    // marginTop: 10, // Margin handled inline now
    textAlign: 'center',
  },
});

export default App;
