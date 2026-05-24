//
//  MGRSProperties.swift
//  mgrs-ios
//
//  Created by Brian Osborn on 8/23/22.
//

import Foundation
import Grid

/**
 * MGRS property loader
 */
public class MGRSProperties: GridProperties {
        
    /**
     * Properties Name
     */
    public static let PROPERTIES_NAME = "mgrs"
    
    /**
     * Singleton instance
     */
    private static let _instance: MGRSProperties = {
        guard let url = Bundle.module.url(forResource: PROPERTIES_NAME, withExtension: PropertyConstants.PROPERTY_LIST_TYPE) else {
            fatalError("Unable to find required resource: \(PROPERTIES_NAME).\(PropertyConstants.PROPERTY_LIST_TYPE)")
        }

        guard let data = try? Data(contentsOf: url) else {
            fatalError("Unable to load required resource: \(url)")
        }
        return MGRSProperties(data)
    }()
    
    public static var instance: MGRSProperties {
        get {
            return _instance
        }
    }

}
