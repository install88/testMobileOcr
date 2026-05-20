// React Native CLI autolinking config.
// Tells the host app where to find the Android sources for this module.
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.dateocr.rn.DateOcrPackage;',
        packageInstance: 'new DateOcrPackage()',
      },
      ios: null,
    },
  },
}
